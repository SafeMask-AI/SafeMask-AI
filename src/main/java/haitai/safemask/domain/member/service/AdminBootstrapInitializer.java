package haitai.safemask.domain.member.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 최초 설치에서 관리자 부재로 승인 흐름이 잠기는 것을 방지하는 선택적 초기화기입니다. */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

	private final AdminMemberService adminMemberService;
	private final String bootstrapLoginId;

	public AdminBootstrapInitializer(AdminMemberService adminMemberService,
		@Value("${safemask.admin.bootstrap-login-id:}") String bootstrapLoginId) {
		this.adminMemberService = adminMemberService;
		this.bootstrapLoginId = bootstrapLoginId;
	}

	@Override
	public void run(ApplicationArguments args) {
		adminMemberService.bootstrapFirstAdmin(bootstrapLoginId);
	}
}
