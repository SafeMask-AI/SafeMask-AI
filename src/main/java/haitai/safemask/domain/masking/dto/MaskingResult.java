package haitai.safemask.domain.masking.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 마스킹 수행 결과입니다.
 *
 * <p>채팅 파이프라인에서의 사용:
 * <ul>
 *   <li>hasDetections()가 false → 미리보기 없이 바로 GPT 전송 (일상 대화 UX 유지)</li>
 *   <li>true → summary()로 "이름 3건, 전화번호 2건" 요약을 보여주고 사용자 승인 대기</li>
 *   <li>maskedText만 GPT로 나가고, originalText는 사내(DB 원본 컬럼)에만 남습니다</li>
 * </ul>
 *
 * @param originalText 마스킹 전 원문 (사내 전용)
 * @param maskedText   토큰으로 치환된 텍스트 (GPT로 전송 가능한 유일한 형태)
 * @param detections   탐지 내역 (원문 위치 오름차순)
 */
public record MaskingResult(
	String originalText,
	String maskedText,
	List<Detection> detections
) {

	/** 민감정보가 하나라도 탐지되었는지 여부. false면 미리보기 승인 단계를 건너뜁니다. */
	public boolean hasDetections() {
		return !detections.isEmpty();
	}

	/**
	 * 유형별 탐지 건수를 첫 등장 순서대로 집계합니다.
	 * 미리보기 화면의 "이름 3건, 전화번호 2건" 요약에 사용합니다.
	 */
	public Map<MaskingType, Long> summary() {
		return detections.stream()
			.collect(Collectors.groupingBy(Detection::type, LinkedHashMap::new, Collectors.counting()));
	}
}
