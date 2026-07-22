package haitai.safemask.domain.maskingrule.controller;

import haitai.safemask.domain.maskingrule.dto.MaskingRuleAuditResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleStateChangeRequest;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleTestRequest;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleTestResponse;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleUpsertRequest;
import haitai.safemask.domain.maskingrule.service.MaskingRuleService;
import haitai.safemask.domain.member.entity.Member;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/masking-rules")
@RequiredArgsConstructor
public class MaskingRuleController {

	private final MaskingRuleService maskingRuleService;

	@GetMapping
	public List<MaskingRuleResponse> search(
		@RequestParam(defaultValue = "ALL") String status,
		@RequestParam(required = false) String keyword) {
		return maskingRuleService.search(status, keyword);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MaskingRuleResponse create(@RequestBody @Valid MaskingRuleUpsertRequest request,
		@AuthenticationPrincipal Member administrator) {
		return maskingRuleService.create(request, administrator);
	}

	@PutMapping("/{ruleId}")
	public MaskingRuleResponse update(@PathVariable Long ruleId,
		@RequestBody @Valid MaskingRuleUpsertRequest request,
		@AuthenticationPrincipal Member administrator) {
		return maskingRuleService.update(ruleId, request, administrator);
	}

	@PostMapping("/{ruleId}/activate")
	public MaskingRuleResponse activate(@PathVariable Long ruleId,
		@RequestBody @Valid MaskingRuleStateChangeRequest request,
		@AuthenticationPrincipal Member administrator) {
		return maskingRuleService.activate(ruleId, request, administrator);
	}

	@PostMapping("/{ruleId}/deactivate")
	public MaskingRuleResponse deactivate(@PathVariable Long ruleId,
		@RequestBody @Valid MaskingRuleStateChangeRequest request,
		@AuthenticationPrincipal Member administrator) {
		return maskingRuleService.deactivate(ruleId, request, administrator);
	}

	@PostMapping("/{ruleId}/test")
	public MaskingRuleTestResponse test(@PathVariable Long ruleId,
		@RequestBody @Valid MaskingRuleTestRequest request) {
		return maskingRuleService.test(ruleId, request.version(), request.sampleText());
	}

	@GetMapping("/{ruleId}/audits")
	public List<MaskingRuleAuditResponse> audits(@PathVariable Long ruleId) {
		return maskingRuleService.audits(ruleId);
	}
}
