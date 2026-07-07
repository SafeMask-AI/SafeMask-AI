package haitai.safemask.domain.maskingrule.service;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.repository.MaskingRuleRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애플리케이션 기동 시 기본 마스킹 규칙을 등록·갱신합니다.
 *
 * <p>기본 규칙은 이 코드가 원본(source of truth)입니다:
 * <ul>
 *   <li>이름이 같은 규칙이 이미 있으면 패턴·우선순위·설명을 코드 기준으로 갱신합니다.
 *       (규칙 개선이 배포되면 재기동만으로 반영되도록)</li>
 *   <li>단, 관리자가 끈 규칙(enabled=false)은 다시 켜지 않습니다.</li>
 *   <li>관리자가 다른 이름으로 추가한 커스텀 규칙은 건드리지 않습니다.</li>
 * </ul>
 *
 * <p>기본 규칙 설계 방침:
 * <ul>
 *   <li>정규식으로 확실하게 잡히는 유형(주민번호, 카드, 전화, 이메일, IP 등)을 우선 제공</li>
 *   <li>오탐(false positive)을 줄이는 쪽으로 보수적으로 작성 — 못 잡은 건
 *       사용자가 미리보기에서 추가 마스킹으로 보완할 수 있지만,
 *       잘못 잡은 건 대화 품질을 바로 해치기 때문</li>
 *   <li>이름은 외부 NER API 없이(원문 유출 모순) 성씨 휴리스틱으로 시작.
 *       규칙이 한 번 잡은 이름은 엔진의 전파 마스킹이 문장 속 재등장까지 처리</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaskingRuleSeeder implements ApplicationRunner {

	/** 이름 휴리스틱에서 공통으로 쓰는 흔한 성씨 문자 클래스 */
	private static final String SURNAMES =
		"[김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구나민진지엄채원천방공현함변염여추도소석선설마길위표명기반왕금옥육인맹제모탁국어은편용]";

	/**
	 * 이름 탐지 휴리스틱 1 (호칭 문맥):
	 * (흔한 성씨 1자) + (이름 2자) + 바로 뒤따르는 호칭/직급 → 세 글자 성명만 탐지합니다.
	 *
	 * <p>보수적으로 설계한 이유:
	 * <ul>
	 *   <li>이름 2자를 고정해 "이사님" 같은 직급 단어가 이름으로 오탐되는 것을 방지
	 *       (두 글자 이름은 놓치지만 오탐보다 미탐이 낫다는 방침)</li>
	 *   <li>lookahead를 사용해 호칭 자체는 마스킹 범위에 포함하지 않음
	 *       ("김철수 과장" → "[PERSON_001] 과장"으로 치환되어 문맥 유지)</li>
	 * </ul>
	 */
	private static final String NAME_WITH_TITLE_PATTERN =
		"(?<![가-힣])" + SURNAMES + "[가-힣]{2}"
			+ "(?=\\s?(님|씨|과장|부장|차장|대리|사원|주임|팀장|실장|본부장|이사|상무|전무|사장|대표|책임|선임|수석|프로|매니저|고객|회원|담당자|기사"
			+ "|파트장|그룹장|센터장|지점장|부사장|회장|원장|점장|소장|국장|반장|조장|주무관|연구원|교수|강사|위원|간사))";

	/** 단독 표기 이름 휴리스틱에서 성씨로 시작하는 흔한 일반 단어를 제외하는 부정 lookahead */
	private static final String NAME_STOPWORDS = "(?!이메일|이번주|이번달|고객님|회원님|선생님)";

	/**
	 * 이름 탐지 휴리스틱 2 (단독 표기):
	 * 엑셀 이름 컬럼("김민준"), 슬래시 구분 나열("한지아/010-...") 처럼
	 * 구분자 사이에 단독 토큰으로 쓰인 세 글자 성명을 탐지합니다.
	 *
	 * <p>오탐을 막기 위한 장치:
	 * <ul>
	 *   <li>탭·슬래시·쉼표 같은 "실제 구분자"가 앞뒤 중 최소 한쪽에 있어야 함 —
	 *       텍스트 시작/끝·줄바꿈만으로 둘러싸인 토큰은 잡지 않음.
	 *       ("고마워", "고생해" 같은 세 글자 채팅 한 마디가 통째로 이름 오탐되던 문제의 해결책.
	 *       표 데이터는 셀 사이에 탭이 있으므로 이 조건을 자연히 충족함)</li>
	 *   <li>공백은 경계로 인정하지 않음 — "강남구 테헤란로"의 '강남구' 같은
	 *       문장 속 일반 단어는 잡지 않음</li>
	 *   <li>성씨로 시작하는 흔한 일반 단어(이메일, 이번주 등)는 stopword로 제외</li>
	 * </ul>
	 * 문장 속에서 공백으로 둘러싸인 이름("강하린 계정 접속...")은 이 규칙이 못 잡지만,
	 * 같은 텍스트의 이름 컬럼에서 한 번 잡히면 엔진의 전파 마스킹이 함께 가려줍니다.
	 */
	private static final String NAME_STANDALONE_PATTERN =
		// 왼쪽이 실제 구분자(탭·슬래시·쉼표)면 오른쪽은 느슨한 경계(줄바꿈·끝)도 허용
		"(?:(?<=[\\t/,])" + NAME_STOPWORDS + SURNAMES + "[가-힣]{2}(?=[\\t\\n/,]|$)"
			// 오른쪽이 실제 구분자면 왼쪽은 느슨한 경계(시작·줄바꿈)도 허용
			+ "|(?<=^|[\\t\\n/,])" + NAME_STOPWORDS + SURNAMES + "[가-힣]{2}(?=[\\t/,]))";

	/**
	 * 정규식만으로 확정할 수 없어 DetectionPolicies의 후처리 판정과 짝을 이루는
	 * 규칙들의 이름 상수입니다. (규칙 이름이 곧 판정 로직의 연결 고리)
	 */
	public static final String NAME_STANDALONE_RULE_NAME = "이름(단독 표기)";
	public static final String NAME_IN_SENTENCE_RULE_NAME = "이름(문장 속)";
	public static final String CARD_CONTIGUOUS_RULE_NAME = "카드번호(무구분)";
	public static final String PASSPORT_RULE_NAME = "여권번호";

	/**
	 * 이름 탐지 휴리스틱 3 (문장 속, 사전 검증 필수):
	 * "강하린 계정이 잠겼어요", "김수정의 연락처 알려줘"처럼 일반 문장 속에 쓰인
	 * 세 글자 성명을 탐지합니다. 뒤에 조사(은/는/이/가...)가 하나 붙는 형태까지 허용합니다.
	 *
	 * <p>이 패턴 단독으로는 "고마워"(고+마워) 같은 일반 단어도 잡히므로 절대 단독으로
	 * 쓰면 안 되고, 반드시 이름 사전 검증(매칭의 이름 부분이 사전에 있는가)과 함께
	 * 적용해야 합니다. MaskingService가 그 결합을 담당하며, 사전이 비어 있으면
	 * 이 규칙을 통째로 제외합니다.
	 */
	private static final String NAME_IN_SENTENCE_PATTERN =
		"(?<![가-힣])" + SURNAMES + "[가-힣]{2}"
			+ "(?=(?:은|는|이|가|을|를|과|와|도|만|의|께|님|씨|에게|한테|께서)?(?![가-힣]))";

	/**
	 * 도로명 주소 패턴:
	 * (시·도) + (시/군/구/읍/면/동 0회 이상) + (도로명 로/길 + 건물번호) + (동/호/층 상세 0회 이상)
	 * 예: "서울특별시 강남구 테헤란로 123 101동 1203호", "세종특별자치시 한누리대로 1234 1201호",
	 *     "제주특별자치도 제주시 연동 20길 5 201호"
	 * 시·도 접두가 없는 주소("성남시 판교역로 45")는 오탐 위험이 커서 잡지 않습니다.
	 */
	private static final String ADDRESS_PATTERN =
		"[가-힣]{2,6}(?:특별시|광역시|특별자치시|특별자치도|도)"
			+ "(?:\\s[가-힣0-9]{1,10}(?:시|군|구|읍|면|동|리|가))*"
			+ "\\s[가-힣A-Za-z0-9]*(?:로|길)\\s?\\d+(?:-\\d+)?"
			+ "(?:\\s?\\d+(?:동|호|층))*";

	private final MaskingRuleRepository maskingRuleRepository;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		Map<String, MaskingRule> existingByName = maskingRuleRepository.findAll().stream()
			.collect(Collectors.toMap(MaskingRule::getName, Function.identity()));

		int inserted = 0;
		int updated = 0;
		for (MaskingRule defaultRule : defaultRules()) {
			MaskingRule existing = existingByName.get(defaultRule.getName());
			if (existing == null) {
				maskingRuleRepository.save(defaultRule);
				inserted++;
			} else {
				existing.applySeedDefaults(defaultRule.getType(), defaultRule.getPattern(),
					defaultRule.getPriority(), defaultRule.getDescription());
				updated++;
			}
		}
		log.info("기본 마스킹 규칙 동기화 완료: 신규 {}건, 갱신 {}건", inserted, updated);
	}

	/**
	 * 기본 제공 규칙 목록입니다.
	 * 우선순위(낮을수록 먼저)는 "더 구체적인 패턴 먼저" 원칙으로 배치했습니다.
	 * 예: 주민번호(10)·운전면허(15)가 전화(30~31)나 카드(20)보다 먼저 적용되어,
	 * 내부 숫자열이 다른 규칙에 이중 탐지되는 것을 겹침 규칙으로 차단합니다.
	 *
	 * <p>static으로 공개해 테스트에서 실제 운영 패턴을 그대로 검증합니다.
	 */
	public static List<MaskingRule> defaultRules() {
		return List.of(
			MaskingRule.create("주민등록번호", MaskingType.RRN,
				"(?<!\\d)\\d{6}-[1-8]\\d{6}(?!\\d)", 10,
				"하이픈 형식의 주민등록번호·외국인등록번호. 뒷자리 첫 숫자(성별 코드)가 "
					+ "내국인 1~4, 외국인 5~8인 경우만 탐지해 오탐을 줄입니다."),

			MaskingRule.create("운전면허번호", MaskingType.DRIVER_LICENSE,
				"(?<!\\d)\\d{2}-\\d{2}-\\d{6}-\\d{2}(?!\\d)", 15,
				"지역코드(2)-발급연도(2)-일련번호(6)-체크값(2) 형식의 운전면허번호. "
					+ "계좌·카드번호보다 먼저 적용해 숫자 그룹이 이중 탐지되지 않게 합니다."),

			MaskingRule.create("계좌번호", MaskingType.ACCOUNT_NUMBER,
				"(국민|신한|우리|하나|농협|기업|산업|수협|우체국|새마을|씨티|SC제일|대구|부산|광주|전북|경남|제주|케이뱅크|카카오뱅크|토스뱅크|KB|NH|IBK)(은행)?\\s?\\d{2,6}(-\\d{1,7}){1,3}(?!\\d)", 18,
				"은행명 + 하이픈 구분 숫자 그룹(2~4개) 형식의 계좌번호. 은행별로 자릿수가 제각각이라 "
					+ "은행명 문맥을 필수로 요구해 일반 숫자 나열이 계좌로 오탐되는 것을 막습니다. "
					+ "은행명 없이 숫자만 적힌 계좌는 미리보기에서 추가 마스킹으로 보완합니다."),

			MaskingRule.create("신용카드 번호", MaskingType.CARD_NUMBER,
				"(?<!\\d)(?:\\d{4}([- ])\\d{4}\\1\\d{4}\\1\\d{2,4}|\\d{4}([- ])\\d{6}\\2\\d{5})(?!\\d)", 20,
				"구분자(하이픈/공백)가 일관된 카드번호. 16자리(4-4-4-4)와 다이너스 14자리(4-4-4-2), "
					+ "아멕스 15자리(4-6-5)를 지원합니다."),

			MaskingRule.create(CARD_CONTIGUOUS_RULE_NAME, MaskingType.CARD_NUMBER,
				"(?<!\\d)(?:4\\d{15}|5[1-5]\\d{14}|2[2-7]\\d{14}|6011\\d{12}|65\\d{14}|3[47]\\d{13}|35\\d{14})(?!\\d)",
				21,
				"구분자 없이 붙여 쓴 카드번호. 일반 숫자열 오탐을 막기 위해 실제 카드 발급사 "
					+ "번호대(BIN: 비자 4, 마스터 51-55/22-27, 디스커버 6011/65, 아멕스 34/37, JCB 35)로 "
					+ "제한하고, Luhn 체크섬 검증(DetectionPolicies)을 통과한 번호만 탐지합니다."),

			MaskingRule.create("휴대폰 번호", MaskingType.PHONE,
				"(?<!\\d)01[016789][-. ]?\\d{3,4}[-. ]?\\d{4}(?!\\d)", 30,
				"010/011/016/017/018/019로 시작하는 휴대폰 번호. "
					+ "하이픈·점·공백 구분자와 구분자 없이 붙여 쓴 형식을 모두 탐지합니다."),

			MaskingRule.create("유선전화 번호", MaskingType.PHONE,
				"(?<!\\d)0\\d{1,2}[-. ]\\d{3,4}[-. ]\\d{4}(?!\\d)", 31,
				"지역번호(02, 031 등)·인터넷전화(070)로 시작하는 유선전화. "
					+ "일반 숫자열 오탐을 줄이기 위해 구분자가 있는 형식만 탐지합니다."),

			MaskingRule.create("이메일 주소", MaskingType.EMAIL,
				"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", 40,
				"표준 형식의 이메일 주소."),

			MaskingRule.create(PASSPORT_RULE_NAME, MaskingType.PASSPORT,
				"(?<![A-Za-z0-9])[A-Z]\\d{8}(?![A-Za-z0-9])", 45,
				"영문 대문자 1자 + 숫자 8자리 형식의 여권번호. 이메일보다 뒤에 적용해 "
					+ "이메일 주소 내부의 유사 문자열을 잘라 잡지 않게 합니다. "
					+ "앞 문맥에 제품/코드/품번/모델이 있으면 제품코드로 보고 제외합니다(DetectionPolicies)."),

			MaskingRule.create("IP 주소", MaskingType.IP,
				"(?<![\\d.])(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}(?![\\d.])",
				50,
				"IPv4 주소. 각 옥텟이 0~255 범위인 경우만 탐지해 버전 문자열(4.1.0 등) 오탐을 방지합니다."),

			MaskingRule.create("도로명 주소", MaskingType.ADDRESS,
				ADDRESS_PATTERN, 55,
				"시·도로 시작하는 도로명 주소(상세 동/호/층 포함). 이름 규칙보다 먼저 적용해 "
					+ "주소 안의 지명(강남구 등)이 이름으로 오탐되는 것을 구간 선점으로 차단합니다."),

			MaskingRule.create("이름(호칭 문맥)", MaskingType.NAME,
				NAME_WITH_TITLE_PATTERN, 60,
				"흔한 성씨 + 두 글자 이름 + 뒤따르는 호칭/직급으로 세 글자 성명을 탐지하는 휴리스틱. "
					+ "호칭은 문맥 유지를 위해 마스킹 범위에서 제외합니다."),

			MaskingRule.create(NAME_STANDALONE_RULE_NAME, MaskingType.NAME,
				NAME_STANDALONE_PATTERN, 61,
				"표(엑셀 셀)나 구분자(탭/슬래시/쉼표) 사이에 단독으로 쓰인 세 글자 성명을 탐지하는 휴리스틱. "
					+ "실제 구분자가 최소 한쪽에 있어야 하므로 '고마워' 같은 세 글자 채팅 한 마디나 "
					+ "일반 문장 속 단어는 잡지 않으며, 이름 컬럼 값이 아니면 이름 사전 검증을 "
					+ "함께 거칩니다(DetectionPolicies — '고객명' 같은 컬럼 헤더 단어 오탐 방지). "
					+ "한 번 잡힌 이름의 문장 속 재등장은 엔진의 전파 마스킹이 처리합니다."),

			MaskingRule.create(NAME_IN_SENTENCE_RULE_NAME, MaskingType.NAME,
				NAME_IN_SENTENCE_PATTERN, 62,
				"일반 문장 속의 세 글자 성명을 탐지하는 휴리스틱 (뒤따르는 조사 1개까지 허용). "
					+ "이름 사전 검증과 반드시 함께 적용됩니다 — 이름 부분이 사전에 있을 때만 확정되며, "
					+ "사전이 비어 있으면 규칙 자체가 적용에서 제외됩니다."),

			MaskingRule.create("차량번호", MaskingType.VEHICLE_NUMBER,
				"(?<![\\d가-힣])\\d{2,3}[가나다라마바사아자카타파거너더러머버서어저고노도로모보소오조구누두루무부수우주하허호배]\\s?\\d{4}(?!\\d)", 65,
				"자동차 등록번호판(숫자 2~3자리 + 용도 한글 1자 + 숫자 4자리). 번호판 용도 기호로 쓰이는 "
					+ "한글만 허용하고, 주소 규칙보다 뒤에 적용해 주소 속 번지·호수가 오탐되지 않게 합니다.")
		);
	}
}
