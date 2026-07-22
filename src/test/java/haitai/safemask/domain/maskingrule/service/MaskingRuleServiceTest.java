package haitai.safemask.domain.maskingrule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.masking.engine.MaskingEngine;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleUpsertRequest;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleStateChangeRequest;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.entity.MaskingRuleAudit;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleAuditRepository;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MaskingRuleServiceTest {

	private MaskingRuleRepository repository;
	private MaskingRuleAuditRepository auditRepository;
	private MaskingRuleService service;
	private Member administrator;

	@BeforeEach
	void setUp() {
		repository = mock(MaskingRuleRepository.class);
		auditRepository = mock(MaskingRuleAuditRepository.class);
		service = new MaskingRuleService(repository, auditRepository,
			new MaskingRuleDefinitionValidator(), new MaskingEngine());
		administrator = mock(Member.class);
		when(administrator.getId()).thenReturn(7L);
		when(administrator.getName()).thenReturn("보안관리자");
	}

	@Test
	void newCustomRuleIsSavedInactiveAndAudited() {
		var response = service.create(request(null), administrator);

		assertThat(response.origin().name()).isEqualTo("CUSTOM");
		assertThat(response.enabled()).isFalse();
		verify(repository).saveAndFlush(any(MaskingRule.class));
		verify(auditRepository).save(any(MaskingRuleAudit.class));
	}

	@Test
	void systemRuleCannotBeUpdatedThroughAdministratorApi() {
		MaskingRule systemRule = MaskingRule.create("이메일", MaskingType.EMAIL,
			"[a-z]+@[a-z]+\\.com", 10, null);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(systemRule));

		assertThatThrownBy(() -> service.update(1L, request(0L), administrator))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("시스템 마스킹 규칙");
	}

	@Test
	void activeRuleLoadingFailsClosedWhenSystemProtectionIsMissing() {
		MaskingRule custom = MaskingRule.createCustom("코드명", MaskingType.CUSTOM,
			MaskingRuleKind.KEYWORD, "ALPHA", 5000, null, administrator);
		when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(custom));

		assertThatThrownBy(service::activeRules)
			.isInstanceOf(CustomException.class)
			.satisfies(exception -> assertThat(((CustomException) exception).getErrorCode())
				.isEqualTo(ErrorCode.MASKING_RULES_UNAVAILABLE));
	}

	@Test
	void untestedCustomRuleCannotBeActivated() {
		MaskingRule custom = MaskingRule.createCustom("코드명", MaskingType.CUSTOM,
			MaskingRuleKind.KEYWORD, "ALPHA", 5000, null, administrator);
		ReflectionTestUtils.setField(custom, "id", 1L);
		ReflectionTestUtils.setField(custom, "version", 0L);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(custom));

		assertThatThrownBy(() -> service.activate(1L,
			new MaskingRuleStateChangeRequest(0L, "운영 적용"), administrator))
			.isInstanceOf(CustomException.class)
			.satisfies(exception -> assertThat(((CustomException) exception).getErrorCode())
				.isEqualTo(ErrorCode.MASKING_RULE_TEST_REQUIRED));
	}

	private MaskingRuleUpsertRequest request(Long version) {
		return new MaskingRuleUpsertRequest("코드명", MaskingType.CUSTOM,
			MaskingRuleKind.KEYWORD, "ALPHA", 5000, "사내 프로젝트", "보호 대상 추가", version);
	}
}
