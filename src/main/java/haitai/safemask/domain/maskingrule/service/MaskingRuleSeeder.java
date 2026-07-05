package haitai.safemask.domain.maskingrule.service;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애플리케이션 첫 기동 시 기본 마스킹 규칙을 등록합니다.
 *
 * <p>규칙이 하나라도 존재하면 아무것도 하지 않으므로,
 * 관리자가 규칙을 수정·삭제해도 재기동 시 덮어쓰지 않습니다.
 *
 * <p>기본 규칙 설계 방침:
 * <ul>
 *   <li>정규식으로 확실하게 잡히는 유형(주민번호, 카드, 전화, 이메일, IP)을 우선 제공</li>
 *   <li>오탐(false positive)을 줄이는 쪽으로 보수적으로 작성 — 못 잡은 건
 *       사용자가 미리보기에서 추가 마스킹으로 보완할 수 있지만,
 *       잘못 잡은 건 대화 품질을 바로 해치기 때문</li>
 *   <li>이름은 외부 NER API 없이(원문 유출 모순) 성씨+호칭 휴리스틱으로 시작.
 *       회사 인명 사전 등 고도화는 관리자 규칙 추가로 대응</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaskingRuleSeeder implements ApplicationRunner {

	/**
	 * 이름 탐지 휴리스틱:
	 * (흔한 성씨 1자) + (이름 2자) + 바로 뒤따르는 호칭/직급 → 세 글자 성명만 탐지합니다.
	 *
	 * <p>보수적으로 설계한 이유:
	 * <ul>
	 *   <li>이름 2자를 고정해 "이사님" 같은 직급 단어가 이름으로 오탐되는 것을 방지
	 *       (두 글자 이름은 놓치지만 오탐보다 미탐이 낫다는 방침)</li>
	 *   <li>호칭이 뒤에 없는 이름은 잡지 않음 — 사용자가 추가 마스킹으로 보완</li>
	 *   <li>lookahead를 사용해 호칭 자체는 마스킹 범위에 포함하지 않음
	 *       ("김철수 과장" → "[PERSON_001] 과장"으로 치환되어 문맥 유지)</li>
	 * </ul>
	 */
	private static final String NAME_PATTERN =
		"(?<![가-힣])[김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구나민진지엄채원천방공현함변염여추도소석선설마길위표명기반왕금옥육인맹제모탁국어은편용]"
			+ "[가-힣]{2}"
			+ "(?=\\s?(님|씨|과장|부장|차장|대리|사원|주임|팀장|실장|본부장|이사|상무|전무|사장|대표|책임|선임|수석|프로|매니저))";

	private final MaskingRuleRepository maskingRuleRepository;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (maskingRuleRepository.count() > 0) {
			return;
		}

		List<MaskingRule> seeded = maskingRuleRepository.saveAll(defaultRules());
		log.info("기본 마스킹 규칙 {}건을 등록했습니다.", seeded.size());
	}

	/**
	 * 기본 제공 규칙 목록입니다.
	 * 우선순위(낮을수록 먼저)는 "더 구체적인 패턴 먼저" 원칙으로 배치했습니다.
	 * 예: 주민번호(10)가 유선전화(31)보다 먼저 적용되어, 주민번호 뒷부분 숫자열이
	 * 전화번호로 이중 탐지되는 것을 겹침 규칙으로 차단합니다.
	 *
	 * <p>static으로 공개해 테스트에서 실제 운영 패턴을 그대로 검증합니다.
	 */
	public static List<MaskingRule> defaultRules() {
		return List.of(
			MaskingRule.create("주민등록번호", MaskingType.RRN,
				"(?<!\\d)\\d{6}-[1-4]\\d{6}(?!\\d)", 10,
				"하이픈 형식의 주민등록번호. 뒷자리 첫 숫자(성별 코드)가 1~4인 경우만 탐지해 오탐을 줄입니다."),

			MaskingRule.create("신용카드 번호", MaskingType.CARD_NUMBER,
				"(?<!\\d)\\d{4}([- ])\\d{4}\\1\\d{4}\\1\\d{4}(?!\\d)", 20,
				"구분자(하이픈/공백)가 일관된 16자리 카드번호. 구분자 없는 연속 숫자는 오탐 위험이 커서 제외합니다."),

			MaskingRule.create("휴대폰 번호", MaskingType.PHONE,
				"(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)", 30,
				"010/011/016/017/018/019로 시작하는 휴대폰 번호. 구분자 없이 붙여 쓴 형식도 탐지합니다."),

			MaskingRule.create("유선전화 번호", MaskingType.PHONE,
				"(?<!\\d)0\\d{1,2}[- ]\\d{3,4}[- ]\\d{4}(?!\\d)", 31,
				"지역번호로 시작하는 유선전화. 일반 숫자열 오탐을 줄이기 위해 구분자가 있는 형식만 탐지합니다."),

			MaskingRule.create("이메일 주소", MaskingType.EMAIL,
				"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", 40,
				"표준 형식의 이메일 주소."),

			MaskingRule.create("IP 주소", MaskingType.IP,
				"(?<![\\d.])(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}(?![\\d.])",
				50,
				"IPv4 주소. 각 옥텟이 0~255 범위인 경우만 탐지해 버전 문자열(4.1.0 등) 오탐을 방지합니다."),

			MaskingRule.create("이름(호칭 문맥)", MaskingType.NAME,
				NAME_PATTERN, 60,
				"흔한 성씨 + 두 글자 이름 + 뒤따르는 호칭/직급으로 세 글자 성명을 탐지하는 휴리스틱. "
					+ "호칭 없이 단독으로 쓰인 이름은 잡지 못하므로 미리보기의 추가 마스킹으로 보완합니다.")
		);
	}
}
