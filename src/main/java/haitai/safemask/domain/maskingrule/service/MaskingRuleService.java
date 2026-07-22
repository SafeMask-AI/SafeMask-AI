package haitai.safemask.domain.maskingrule.service;

import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.masking.engine.MaskingEngine;
import haitai.safemask.domain.masking.engine.TokenAssigner;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleAuditResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleDetectionResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleStateChangeRequest;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleTestResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleUpsertRequest;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.entity.MaskingRuleAudit;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleAuditAction;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleOrigin;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleAuditRepository;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaskingRuleService {

	private static final Duration ACTIVE_RULE_CACHE_TTL = Duration.ofSeconds(30);
	private final MaskingRuleRepository maskingRuleRepository;
	private final MaskingRuleAuditRepository auditRepository;
	private final MaskingRuleDefinitionValidator definitionValidator;
	private final MaskingEngine maskingEngine;
	private volatile ActiveRuleCache activeRuleCache;

	public List<MaskingRuleResponse> search(String rawStatus, String rawKeyword) {
		String status = rawStatus == null ? "ALL" : rawStatus.trim().toUpperCase(Locale.ROOT);
		if (!List.of("ALL", "SYSTEM", "ACTIVE", "INACTIVE").contains(status)) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, "지원하지 않는 규칙 상태입니다.");
		}
		String keyword = rawKeyword == null ? "" : rawKeyword.trim().toLowerCase(Locale.ROOT);
		return maskingRuleRepository.findAllByOrderByOriginAscPriorityAscNameAsc().stream()
			.filter(rule -> matchesStatus(rule, status))
			.filter(rule -> keyword.isEmpty()
				|| rule.getName().toLowerCase(Locale.ROOT).contains(keyword)
				|| (rule.getDescription() != null
					&& rule.getDescription().toLowerCase(Locale.ROOT).contains(keyword)))
			.map(MaskingRuleResponse::from)
			.toList();
	}

	@Transactional
	public MaskingRuleResponse create(MaskingRuleUpsertRequest request, Member administrator) {
		var valid = definitionValidator.validate(request);
		if (maskingRuleRepository.countByNameIgnoreCase(valid.name()) > 0) {
			throw new CustomException(ErrorCode.MASKING_RULE_DUPLICATE);
		}
		MaskingRule rule = MaskingRule.createCustom(valid.name(), valid.type(), valid.ruleKind(),
			valid.pattern(), valid.priority(), valid.description(), administrator);
		saveRule(rule);
		recordAudit(rule, MaskingRuleAuditAction.CREATED, administrator, valid.reason());
		invalidateActiveRuleCache();
		return MaskingRuleResponse.from(rule);
	}

	@Transactional
	public MaskingRuleResponse update(Long ruleId, MaskingRuleUpsertRequest request, Member administrator) {
		var valid = definitionValidator.validate(request);
		MaskingRule rule = findForUpdate(ruleId);
		assertCustom(rule);
		assertVersion(rule, request.version());
		if (maskingRuleRepository.countByNameIgnoreCaseAndIdNot(valid.name(), ruleId) > 0) {
			throw new CustomException(ErrorCode.MASKING_RULE_DUPLICATE);
		}
		rule.updateCustom(valid.name(), valid.type(), valid.ruleKind(), valid.pattern(),
			valid.priority(), valid.description(), administrator);
		saveRule(rule);
		recordAudit(rule, MaskingRuleAuditAction.UPDATED, administrator, valid.reason());
		invalidateActiveRuleCache();
		return MaskingRuleResponse.from(rule);
	}

	@Transactional
	public MaskingRuleResponse activate(Long ruleId, MaskingRuleStateChangeRequest request,
		Member administrator) {
		MaskingRule rule = findForUpdate(ruleId);
		assertCustom(rule);
		assertVersion(rule, request.version());
		definitionValidator.validateStored(rule.getRuleKind(), rule.getType(), rule.getPattern());
		if (!rule.isTestedCurrentDefinition()) {
			throw new CustomException(ErrorCode.MASKING_RULE_TEST_REQUIRED);
		}
		if (Boolean.TRUE.equals(rule.getEnabled())) {
			return MaskingRuleResponse.from(rule);
		}
		rule.activate(administrator);
		maskingRuleRepository.saveAndFlush(rule);
		recordAudit(rule, MaskingRuleAuditAction.ACTIVATED, administrator, request.reason().trim());
		invalidateActiveRuleCache();
		return MaskingRuleResponse.from(rule);
	}

	@Transactional
	public MaskingRuleResponse deactivate(Long ruleId, MaskingRuleStateChangeRequest request,
		Member administrator) {
		MaskingRule rule = findForUpdate(ruleId);
		assertCustom(rule);
		assertVersion(rule, request.version());
		if (!Boolean.TRUE.equals(rule.getEnabled())) {
			return MaskingRuleResponse.from(rule);
		}
		rule.deactivate(administrator);
		maskingRuleRepository.saveAndFlush(rule);
		recordAudit(rule, MaskingRuleAuditAction.DEACTIVATED, administrator, request.reason().trim());
		invalidateActiveRuleCache();
		return MaskingRuleResponse.from(rule);
	}

	@Transactional
	public MaskingRuleTestResponse test(Long ruleId, Long expectedVersion, String sampleText) {
		MaskingRule rule = findForUpdate(ruleId);
		assertVersion(rule, expectedVersion);
		if (!rule.isSystemRule()) {
			definitionValidator.validateStored(rule.getRuleKind(), rule.getType(), rule.getPattern());
		}
		MaskingResult result = maskingEngine.mask(sampleText, List.of(rule), new PreviewTokenAssigner());
		if (!rule.isSystemRule()) {
			rule.markTested();
			maskingRuleRepository.saveAndFlush(rule);
		}
		return new MaskingRuleTestResponse(result.maskedText(), result.detections().size(),
			result.detections().stream().map(MaskingRuleDetectionResponse::from).toList(), rule.getVersion());
	}

	public List<MaskingRuleAuditResponse> audits(Long ruleId) {
		if (!maskingRuleRepository.existsById(ruleId)) {
			throw new CustomException(ErrorCode.MASKING_RULE_NOT_FOUND);
		}
		return auditRepository.findRecentByMaskingRuleId(ruleId).stream()
			.map(MaskingRuleAuditResponse::from)
			.toList();
	}

	/**
	 * 채팅 마스킹에서 사용할 최근 검증 규칙 스냅샷입니다.
	 * DB 장애 시 이미 검증된 캐시는 사용할 수 있지만 최초 로딩 실패나 시스템 규칙 누락은
	 * 원문 외부 전송으로 이어질 수 있으므로 요청을 실패시킵니다.
	 */
	public List<MaskingRule> activeRules() {
		ActiveRuleCache cached = activeRuleCache;
		Instant now = Instant.now();
		if (cached != null && now.isBefore(cached.expiresAt())) {
			return cached.rules();
		}
		synchronized (this) {
			cached = activeRuleCache;
			if (cached != null && now.isBefore(cached.expiresAt())) {
				return cached.rules();
			}
			try {
				List<MaskingRule> loaded = List.copyOf(maskingRuleRepository.findByEnabledTrueOrderByPriorityAsc());
				if (loaded.stream().noneMatch(MaskingRule::isSystemRule)) {
					throw new CustomException(ErrorCode.MASKING_RULES_UNAVAILABLE);
				}
				for (MaskingRule rule : loaded) {
					if (!rule.isSystemRule()) {
						definitionValidator.validateStored(rule.getRuleKind(), rule.getType(), rule.getPattern());
					}
				}
				activeRuleCache = new ActiveRuleCache(loaded, now.plus(ACTIVE_RULE_CACHE_TTL));
				return loaded;
			} catch (CustomException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				if (cached != null) {
					return cached.rules();
				}
				throw new CustomException(ErrorCode.MASKING_RULES_UNAVAILABLE, exception);
			}
		}
	}

	public void invalidateActiveRuleCache() {
		activeRuleCache = null;
	}

	private boolean matchesStatus(MaskingRule rule, String status) {
		return switch (status) {
			case "SYSTEM" -> rule.getOrigin() == MaskingRuleOrigin.SYSTEM;
			case "ACTIVE" -> rule.getOrigin() == MaskingRuleOrigin.CUSTOM && Boolean.TRUE.equals(rule.getEnabled());
			case "INACTIVE" -> rule.getOrigin() == MaskingRuleOrigin.CUSTOM && !Boolean.TRUE.equals(rule.getEnabled());
			default -> true;
		};
	}

	private MaskingRule findForUpdate(Long ruleId) {
		return maskingRuleRepository.findByIdForUpdate(ruleId)
			.orElseThrow(() -> new CustomException(ErrorCode.MASKING_RULE_NOT_FOUND));
	}

	private void assertCustom(MaskingRule rule) {
		if (rule.isSystemRule()) {
			throw new CustomException(ErrorCode.MASKING_RULE_SYSTEM_IMMUTABLE);
		}
	}

	private void assertVersion(MaskingRule rule, Long expectedVersion) {
		if (expectedVersion == null || !expectedVersion.equals(rule.getVersion())) {
			throw new CustomException(ErrorCode.MASKING_RULE_VERSION_CONFLICT);
		}
	}

	private void recordAudit(MaskingRule rule, MaskingRuleAuditAction action, Member administrator,
		String reason) {
		auditRepository.save(MaskingRuleAudit.snapshot(rule, action, administrator.getId(),
			administrator.getName(), reason));
	}

	/** 사전 중복 확인 이후의 동시 등록 경합도 DB 고유 제약 기준으로 일관되게 응답합니다. */
	private void saveRule(MaskingRule rule) {
		try {
			maskingRuleRepository.saveAndFlush(rule);
		} catch (DataIntegrityViolationException exception) {
			throw new CustomException(ErrorCode.MASKING_RULE_DUPLICATE, exception);
		}
	}

	private record ActiveRuleCache(List<MaskingRule> rules, Instant expiresAt) {
	}

	private static final class PreviewTokenAssigner implements TokenAssigner {
		private final Map<MaskingType, Integer> counters = new EnumMap<>(MaskingType.class);
		private final Map<String, String> tokens = new HashMap<>();

		@Override
		public String assign(MaskingType type, String value) {
			return tokens.computeIfAbsent(type.name() + '\u0000' + value, ignored ->
				"[" + type.name() + "_" + String.format("%03d", counters.merge(type, 1, Integer::sum)) + "]");
		}
	}
}
