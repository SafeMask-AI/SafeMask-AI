package haitai.safemask.domain.auth.service;

import haitai.safemask.domain.auth.dto.DuplicateCheckResponse;
import haitai.safemask.domain.auth.dto.LoginRequest;
import haitai.safemask.domain.auth.dto.LoginResponse;
import haitai.safemask.domain.auth.dto.LoginResult;
import haitai.safemask.domain.auth.dto.SignupRequest;
import haitai.safemask.domain.auth.dto.SignupResponse;
import haitai.safemask.domain.auth.dto.TokenRefreshResult;
import haitai.safemask.domain.auth.entity.RefreshToken;
import haitai.safemask.domain.auth.repository.RefreshTokenRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import haitai.safemask.global.jwt.JwtProperties;
import haitai.safemask.global.jwt.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입과 로그인, 토큰 갱신/로그아웃을 담당하는 서비스입니다.
 *
 * 인증 흐름 요약:
 * 1. 회원가입: 사번/이메일 중복 확인(단계별 API) → 최종 가입(비밀번호 BCrypt 해싱 후 저장)
 * 2. 로그인: 사번 조회 + 비밀번호 대조 → Access Token(JWT) + Refresh Token(UUID, DB 저장) 발급
 * 3. 갱신: Refresh Token 검증 → 새 Access/Refresh Token 발급 (기존 Refresh Token 폐기 = rotation)
 * 4. 로그아웃: DB의 Refresh Token 삭제로 즉시 무효화
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtProperties jwtProperties;

	public AuthService(MemberRepository memberRepository,
		RefreshTokenRepository refreshTokenRepository,
		PasswordEncoder passwordEncoder,
		JwtTokenProvider jwtTokenProvider,
		JwtProperties jwtProperties) {
		this.memberRepository = memberRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenProvider = jwtTokenProvider;
		this.jwtProperties = jwtProperties;
	}

	// ==================== 회원가입 (단계별) ====================

	/**
	 * 회원가입 1단계: 사번 중복 여부를 확인합니다.
	 * 프론트는 exists가 false일 때만 다음 단계로 진행합니다.
	 */
	public DuplicateCheckResponse checkLoginId(String loginId) {
		return new DuplicateCheckResponse(memberRepository.countByLoginId(loginId) > 0);
	}

	/**
	 * 회원가입 2단계: 이메일 중복 여부를 확인합니다.
	 */
	public DuplicateCheckResponse checkEmail(String email) {
		return new DuplicateCheckResponse(memberRepository.countByEmail(email) > 0);
	}

	/**
	 * 회원가입 최종 단계: 모든 입력값을 받아 회원을 생성합니다.
	 *
	 * 단계별 확인을 통과했더라도 그 사이에 다른 사용자가 같은 값으로
	 * 가입했을 수 있으므로, 저장 직전에 중복을 다시 검증합니다.
	 * 비밀번호는 BCrypt로 해싱하여 평문이 DB에 남지 않도록 합니다.
	 */
	@Transactional
	public SignupResponse signup(SignupRequest request) {
		if (memberRepository.countByLoginId(request.loginId()) > 0) {
			throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
		}
		if (memberRepository.countByEmail(request.email()) > 0) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}

		Member member = Member.create(
			request.loginId(),
			passwordEncoder.encode(request.password()),
			request.name(),
			request.email(),
			request.department()
		);
		memberRepository.save(member);

		return SignupResponse.from(member);
	}

	// ==================== 로그인 ====================

	/**
	 * 사번과 비밀번호로 로그인합니다.
	 *
	 * 사번이 없을 때와 비밀번호가 틀렸을 때 모두 동일한 LOGIN_FAILED를 던져
	 * 외부에서 가입된 사번을 추측할 수 없게 합니다.
	 * 성공 시 Access Token(JWT)과 Refresh Token(UUID)을 함께 발급하며,
	 * Refresh Token은 컨트롤러가 HttpOnly 쿠키로 내려줍니다.
	 */
	@Transactional
	public LoginResult login(LoginRequest request) {
		Member member = memberRepository.findByLoginId(request.loginId())
			.orElseThrow(() -> new CustomException(ErrorCode.LOGIN_FAILED));

		// matches()가 입력 평문을 해싱해 저장된 해시와 비교합니다.
		if (!passwordEncoder.matches(request.password(), member.getPassword())) {
			throw new CustomException(ErrorCode.LOGIN_FAILED);
		}

		String accessToken = jwtTokenProvider.createAccessToken(member.getId());
		String refreshToken = issueRefreshToken(member);

		return new LoginResult(LoginResponse.of(member, accessToken), refreshToken);
	}

	// ==================== 토큰 갱신 / 로그아웃 ====================

	/**
	 * Refresh Token으로 새 토큰 쌍을 발급합니다. (Rotation 방식)
	 *
	 * 사용된 Refresh Token은 즉시 폐기되고 새 토큰으로 교체되므로,
	 * 탈취된 토큰이 재사용되면 DB에 존재하지 않아 거부됩니다.
	 * 만료된 토큰은 삭제 후 재로그인을 유도합니다.
	 */
	@Transactional
	public TokenRefreshResult refresh(String rawRefreshToken) {
		RefreshToken stored = refreshTokenRepository.findByToken(rawRefreshToken)
			.orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));

		if (stored.isExpired()) {
			refreshTokenRepository.delete(stored);
			throw new CustomException(ErrorCode.EXPIRED_REFRESH_TOKEN);
		}

		Member member = stored.getMember();

		String newAccessToken = jwtTokenProvider.createAccessToken(member.getId());
		// issueRefreshToken 내부에서 기존 토큰을 모두 삭제한 뒤 새로 발급합니다.
		String newRefreshToken = issueRefreshToken(member);

		return new TokenRefreshResult(newAccessToken, newRefreshToken);
	}

	/**
	 * 로그아웃: 클라이언트가 보낸 Refresh Token을 DB에서 삭제합니다.
	 *
	 * Access Token은 서버에 저장하지 않으므로(무상태) 만료 전까지는 유효하지만,
	 * Refresh Token이 삭제되면 재발급이 불가능해 세션이 자연 종료됩니다.
	 * 이미 삭제된(존재하지 않는) 토큰이어도 로그아웃은 실패시키지 않습니다.
	 */
	@Transactional
	public void logout(String rawRefreshToken) {
		refreshTokenRepository.findByToken(rawRefreshToken)
			.ifPresent(refreshTokenRepository::delete);
	}

	// ==================== private ====================

	/**
	 * 새 Refresh Token을 발급합니다.
	 *
	 * 발급 전에 해당 회원의 기존 토큰을 모두 삭제하여
	 * "회원당 유효 Refresh Token 1개" 정책을 유지합니다.
	 * 토큰 값은 추측이 불가능한 UUID를 사용하고, 만료 시각은 설정값을 따릅니다.
	 */
	private String issueRefreshToken(Member member) {
		refreshTokenRepository.deleteByMember(member);

		String tokenValue = UUID.randomUUID().toString();
		LocalDateTime expiresAt = LocalDateTime.now()
			.plusSeconds(jwtProperties.getRefreshTokenExpiration() / 1000);

		refreshTokenRepository.save(new RefreshToken(tokenValue, member, expiresAt));
		return tokenValue;
	}
}
