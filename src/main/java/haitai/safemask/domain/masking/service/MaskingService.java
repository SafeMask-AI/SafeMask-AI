package haitai.safemask.domain.masking.service;

import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.masking.engine.DetectionPolicies;
import haitai.safemask.domain.masking.engine.MaskingEngine;
import haitai.safemask.domain.masking.store.TokenMappingStore;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleRepository;
import haitai.safemask.domain.maskingrule.service.MaskingRuleSeeder;
import haitai.safemask.domain.namedictionary.service.NameDictionaryService;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마스킹 파이프라인의 진입점입니다.
 * 활성 규칙 조회(DB) + 탐지/치환(엔진) + 토큰 매핑(Redis)을 하나로 묶습니다.
 *
 * 채팅 흐름에서의 사용 순서:
 * 1. mask(): 사용자 메시지를 마스킹. 탐지 0건이면 바로 GPT 전송,
 *    탐지가 있으면 summary()로 유형별 건수를 보여주고 승인 대기
 * 2. maskManually(): 사용자가 미리보기에서 직접 지정한 값을 추가 마스킹
 * 3. (GPT 호출 — 마스킹된 텍스트만 전송)
 * 4. restore(): GPT 응답의 토큰을 원본값으로 원복해 화면에 표시
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaskingService {

	private final MaskingRuleRepository maskingRuleRepository;
	private final TokenMappingStore tokenMappingStore;
	private final MaskingEngine maskingEngine;
	private final NameDictionaryService nameDictionaryService;

	/**
	 * 텍스트에서 민감정보를 탐지해 토큰으로 치환합니다.
	 * 결과의 maskedText만 GPT로 전송할 수 있으며, originalText는 사내에만 보관합니다.
	 */
	public MaskingResult mask(Long chatRoomId, String text) {
		validateText(text);

		return maskingEngine.mask(text, resolveActiveRules(),
			(type, value) -> tokenMappingStore.getOrCreateToken(chatRoomId, type, value),
			DetectionPolicies.standard(text, nameDictionaryService.snapshot()));
	}

	/**
	 * 활성 규칙을 조회하되, 이름 사전이 비어 있으면 "이름(문장 속)" 규칙을 제외합니다.
	 * 이 규칙은 사전 검증 없이는 성씨로 시작하는 세 글자 일반 단어("고마워" 등)를
	 * 마구 잡기 때문에, 사전 없이 단독으로 켜지면 안 됩니다.
	 */
	private List<MaskingRule> resolveActiveRules() {
		List<MaskingRule> activeRules = maskingRuleRepository.findByEnabledTrueOrderByPriorityAsc();
		if (!nameDictionaryService.isEmpty()) {
			return activeRules;
		}
		return activeRules.stream()
			.filter(rule -> !MaskingRuleSeeder.NAME_IN_SENTENCE_RULE_NAME.equals(rule.getName()))
			.toList();
	}


	/**
	 * 사용자가 미리보기 화면에서 직접 지정한 값을 추가로 마스킹합니다.
	 * 유형을 지정하지 않으면 CUSTOM으로 분류합니다.
	 *
	 * @param text  추가 마스킹을 적용할 텍스트 (보통 1차 마스킹이 끝난 미리보기 텍스트)
	 * @param value 사용자가 가리고 싶은 원본값 (정규식이 아닌 리터럴로 처리)
	 */
	public MaskingResult maskManually(Long chatRoomId, String text, String value, MaskingType type) {
		validateText(text);
		if (value == null || value.isBlank()) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}

		MaskingType effectiveType = type != null ? type : MaskingType.CUSTOM;
		return maskingEngine.maskManually(text, value, effectiveType,
			(maskingType, original) -> tokenMappingStore.getOrCreateToken(chatRoomId, maskingType, original));
	}

	/**
	 * GPT 응답 속 마스킹 토큰을 원본값으로 원복합니다.
	 * 매핑이 만료된 토큰은 그대로 남습니다. (임의 값 대체보다 안전)
	 */
	public String restore(Long chatRoomId, String maskedText) {
		validateText(maskedText);

		return maskingEngine.restore(maskedText,
			token -> tokenMappingStore.findOriginal(chatRoomId, token));
	}

	/**
	 * 채팅방의 원본↔토큰 매핑을 즉시 파기합니다.
	 * 방 삭제 시 호출해 TTL 만료를 기다리지 않고 원본값을 제거합니다.
	 */
	public void clearMappings(Long chatRoomId) {
		tokenMappingStore.deleteMappings(chatRoomId);
	}

	private void validateText(String text) {
		if (text == null || text.isBlank()) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}
}
