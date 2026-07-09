package haitai.safemask.domain.masking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.service.MaskingRuleSeeder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * MaskingEngine 단위 테스트입니다.
 *
 * <p>규칙은 실제 운영에 시드되는 MaskingRuleSeeder.defaultRules()를 그대로 사용해
 * "운영 패턴이 실제로 이렇게 동작한다"를 검증합니다.
 * 토큰 발급은 Redis 대신 인메모리 구현(InMemoryTokenAssigner)을 주입합니다.
 */
class MaskingEngineTest {

	private MaskingEngine engine;
	private InMemoryTokenAssigner assigner;
	private List<MaskingRule> rules;

	@BeforeEach
	void setUp() {
		engine = new MaskingEngine();
		assigner = new InMemoryTokenAssigner();
		rules = MaskingRuleSeeder.defaultRules();
	}

	/** 운영과 같은 판정을 재현하기 위한 테스트용 이름 사전 (성을 뺀 이름 부분) */
	private static final Set<String> GIVEN_NAMES = Set.of(
		"철수", "왕기", "서연", "민준", "지아", "지훈", "민서", "수정", "하린", "도윤", "지원");

	/**
	 * 운영(MaskingService.mask)과 동일한 구성으로 마스킹합니다:
	 * 이름 사전·이름 컬럼·Luhn·문맥 판정이 담긴 표준 정책(DetectionPolicies)을 그대로 사용.
	 */
	private MaskingResult mask(String text) {
		return engine.mask(text, rules, assigner, DetectionPolicies.standard(text, GIVEN_NAMES));
	}

	@Nested
	@DisplayName("탐지가 없는 일상 대화")
	class NoDetection {

		@Test
		@DisplayName("민감정보가 없으면 원문이 그대로 유지되고 탐지 내역이 비어 있다")
		void plainChat() {
			MaskingResult result = mask("안녕! 오늘 회의 자료 정리 좀 도와줘.");

			assertThat(result.hasDetections()).isFalse();
			assertThat(result.maskedText()).isEqualTo("안녕! 오늘 회의 자료 정리 좀 도와줘.");
			assertThat(result.summary()).isEmpty();
		}
	}

	@Nested
	@DisplayName("전화번호 탐지")
	class Phone {

		@Test
		@DisplayName("하이픈 형식 휴대폰 번호를 탐지해 토큰으로 치환한다")
		void mobileWithHyphen() {
			MaskingResult result = mask("제 번호는 010-1234-5678 입니다.");

			assertThat(result.detections()).hasSize(1);
			Detection detection = result.detections().get(0);
			assertThat(detection.type()).isEqualTo(MaskingType.PHONE);
			assertThat(detection.originalValue()).isEqualTo("010-1234-5678");
			assertThat(result.maskedText()).isEqualTo("제 번호는 " + detection.token() + " 입니다.");
			assertThat(result.maskedText()).doesNotContain("010-1234-5678");
		}

		@Test
		@DisplayName("구분자 없이 붙여 쓴 휴대폰 번호도 탐지한다")
		void mobileWithoutSeparator() {
			MaskingResult result = mask("01012345678로 연락주세요");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("01012345678");
		}

		@Test
		@DisplayName("휴대폰/유선 두 규칙에 모두 걸리는 번호도 우선순위 규칙 하나로만 탐지된다")
		void noDoubleDetectionByOverlappingRules() {
			// 010-1234-5678은 휴대폰(30)과 유선전화(31) 패턴 모두에 매칭되지만,
			// 먼저 적용된 휴대폰 규칙이 구간을 선점하므로 탐지는 1건이어야 한다.
			MaskingResult result = mask("010-1234-5678");

			assertThat(result.detections()).hasSize(1);
		}

		@Test
		@DisplayName("같은 번호가 여러 번 나오면 항상 같은 토큰으로 치환된다")
		void sameValueSameToken() {
			MaskingResult result = mask(
				"010-1234-5678로 걸었는데 안 받아서 010-1234-5678로 문자 남겼어");

			assertThat(result.detections()).hasSize(2);
			assertThat(result.detections().get(0).token()).isEqualTo(result.detections().get(1).token());
			assertThat(result.summary()).containsEntry(MaskingType.PHONE, 2L);
		}

		@Test
		@DisplayName("다른 번호에는 다른 토큰이 발급된다")
		void differentValueDifferentToken() {
			MaskingResult result = mask("010-1111-2222랑 010-3333-4444");

			assertThat(result.detections()).hasSize(2);
			assertThat(result.detections().get(0).token()).isNotEqualTo(result.detections().get(1).token());
		}
	}

	@Nested
	@DisplayName("주민등록번호 탐지")
	class Rrn {

		@Test
		@DisplayName("주민등록번호는 RRN 1건으로만 탐지된다 (내부 숫자열이 다른 규칙에 이중 탐지되지 않음)")
		void rrnDetectedOnce() {
			MaskingResult result = mask("주민번호는 990101-1234567 입니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.RRN);
			assertThat(result.maskedText()).doesNotContain("990101-1234567");
		}

		@Test
		@DisplayName("외국인등록번호(성별 코드 5~8)도 RRN으로 탐지된다")
		void foreignerRegistrationNumberDetected() {
			MaskingResult result = mask("외국인등록번호 990101-5234567 확인 부탁");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.RRN);
			assertThat(result.maskedText()).doesNotContain("990101-5234567");
		}

		@Test
		@DisplayName("성별 코드가 1~8 범위 밖이면 주민번호로 보지 않는다")
		void invalidGenderCodeNotDetected() {
			MaskingResult result = mask("코드값 990101-9234567 확인");

			assertThat(result.detections()
				.stream()
				.filter(d -> d.type() == MaskingType.RRN))
				.isEmpty();
		}
	}

	@Nested
	@DisplayName("이메일 / IP / 카드번호 탐지")
	class EmailIpCard {

		@Test
		@DisplayName("이메일 주소를 탐지한다")
		void email() {
			MaskingResult result = mask("회신은 hong.wk@haitai.co.kr로 부탁드립니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.EMAIL);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("hong.wk@haitai.co.kr");
		}

		@Test
		@DisplayName("IPv4 주소를 탐지한다")
		void ipv4() {
			MaskingResult result = mask("운영 서버 192.168.10.25 에 배포했습니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.IP);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("192.168.10.25");
		}

		@Test
		@DisplayName("버전 문자열(세 자리)이나 옥텟 범위를 벗어난 숫자는 IP로 오탐하지 않는다")
		void ipFalsePositives() {
			assertThat(mask("스프링부트 4.1.0으로 올렸어요").hasDetections()).isFalse();
			assertThat(mask("일련번호 1234.5.6.7 조회").hasDetections()).isFalse();
		}

		@Test
		@DisplayName("구분자가 일관된 16자리 카드번호를 탐지한다")
		void cardNumber() {
			MaskingResult result = mask("법인카드 1234-5678-9012-3456 결제분입니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.CARD_NUMBER);
		}
	}

	@Nested
	@DisplayName("이름 휴리스틱 탐지")
	class Name {

		@Test
		@DisplayName("성씨+이름 2자 뒤에 직급이 오면 이름만 탐지한다 (직급은 문맥 유지를 위해 남김)")
		void nameFollowedByTitle() {
			MaskingResult result = mask("김철수 과장님께 보고했습니다");

			assertThat(result.detections()).hasSize(1);
			Detection detection = result.detections().get(0);
			assertThat(detection.type()).isEqualTo(MaskingType.NAME);
			assertThat(detection.originalValue()).isEqualTo("김철수");
			// 호칭(과장님)은 마스킹되지 않고 남아 GPT가 문맥을 이해할 수 있다
			assertThat(result.maskedText()).isEqualTo(detection.token() + " 과장님께 보고했습니다");
		}

		@Test
		@DisplayName("'님' 호칭 앞의 이름도 탐지한다")
		void nameFollowedByNim() {
			MaskingResult result = mask("홍왕기님이 요청한 자료입니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("홍왕기");
		}

		@Test
		@DisplayName("'파트장' 등 확장 직책 앞의 이름도 탐지한다")
		void nameFollowedByExtendedTitles() {
			// "빛솔"은 사전에 없는 이름 — 호칭이 강한 근거라 사전 없이도 잡혀야 한다
			MaskingResult result = mask("김빛솔 파트장께 전달했습니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("김빛솔");
			assertThat(result.maskedText()).contains("파트장께");
		}

		@Test
		@DisplayName("'이사님' 같은 직급 단어를 이름으로 오탐하지 않는다")
		void titleWordNotDetectedAsName() {
			MaskingResult result = mask("이사님과 회의가 있습니다");

			assertThat(result.hasDetections()).isFalse();
		}

		@Test
		@DisplayName("'고마워' 같은 세 글자 채팅 한 마디를 이름으로 오탐하지 않는다")
		void shortChatWordNotDetectedAsName() {
			// 성씨 글자(고/이/정...)로 시작하는 세 글자 단어가 메시지 전체일 때
			// 단독 표기 규칙에 걸리던 회귀 방지 테스트
			assertThat(mask("고마워").hasDetections()).isFalse();
			assertThat(mask("고생해").hasDetections()).isFalse();
			assertThat(mask("정리해").hasDetections()).isFalse();
		}

		@Test
		@DisplayName("줄바꿈만으로 나뉜 여러 줄 채팅도 이름으로 오탐하지 않는다")
		void multiLineChatNotDetectedAsName() {
			MaskingResult result = mask("고마워\n이상해\n확인 부탁해");

			assertThat(result.hasDetections()).isFalse();
		}

		@Test
		@DisplayName("실제 구분자(탭/슬래시) 사이의 단독 이름은 계속 탐지한다")
		void standaloneNameWithRealDelimiter() {
			// 엑셀 셀(탭 경계)과 슬래시 나열 형태 — 구분자 조건을 강화해도 유지되어야 하는 케이스
			assertThat(mask("이름\t김민준\t총무팀").summary())
				.containsEntry(MaskingType.NAME, 1L);
			assertThat(mask("한지아/010-1234-5678").summary())
				.containsEntry(MaskingType.NAME, 1L);
		}
	}

	@Nested
	@DisplayName("이름 사전 기반 문장 속 이름 탐지")
	class InSentenceNameWithDictionary {

		@Test
		@DisplayName("문장 속 '성씨+사전 이름' 조합을 탐지한다")
		void detectsNameInPlainSentence() {
			MaskingResult result = mask("김수정 데이터 정리 부탁해");

			assertThat(result.summary()).containsEntry(MaskingType.NAME, 1L);
			assertThat(result.maskedText()).doesNotContain("김수정");
		}

		@Test
		@DisplayName("조사가 붙은 이름도 탐지한다 (조사는 마스킹 범위에서 제외)")
		void detectsNameFollowedByParticle() {
			MaskingResult result = mask("강하린의 계정이 잠겼습니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("강하린");
			assertThat(result.maskedText()).endsWith("의 계정이 잠겼습니다");
		}

		@Test
		@DisplayName("이름 부분이 사전에 없으면 문장 속 세 글자 단어를 잡지 않는다")
		void ignoresWordsNotInDictionary() {
			// "마워"·"생해"는 사전에 없으므로 성씨 글자로 시작해도 이름이 아니라고 판정
			assertThat(mask("고마워 오늘도 고생해").hasDetections()).isFalse();
		}

		@Test
		@DisplayName("사전에 있는 이름이라도 성씨 없이 단독으로 쓰이면 잡지 않는다")
		void ignoresGivenNameWithoutSurname() {
			// "수정"은 사전에 있지만 성씨와 결합하지 않은 일반 단어 사용은 그대로 둔다
			MaskingResult result = mask("이 문서 수정 부탁해요");

			assertThat(result.hasDetections()).isFalse();
			assertThat(result.maskedText()).isEqualTo("이 문서 수정 부탁해요");
		}
	}

	@Nested
	@DisplayName("이름 컬럼 인지와 판정 정책 (DetectionPolicies)")
	class DetectionPolicy {

		@Test
		@DisplayName("'고객명', '신청자' 같은 컬럼 헤더 단어를 이름으로 오탐하지 않는다")
		void headerWordsNotDetectedAsName() {
			// 실제 테스트 파일에서 이름 76건(실제 66건) 오탐을 만들었던 케이스들
			MaskingResult result = mask("케이스ID\t고객명\t신청자\t주의룰\t지원일");

			assertThat(result.hasDetections()).isFalse();
		}

		@Test
		@DisplayName("이름 컬럼 아래의 값은 사전에 없는 희귀 이름이라도 탐지한다")
		void nameColumnValuesDetectedWithoutDictionary() {
			// "빛솔"은 테스트 사전에 없지만, 헤더가 '이름'인 컬럼의 값이므로 이름으로 확정
			MaskingResult result = mask("이름\t부서\n김빛솔\t총무팀");

			assertThat(result.summary()).containsEntry(MaskingType.NAME, 1L);
			assertThat(result.maskedText()).doesNotContain("김빛솔");
		}

		@Test
		@DisplayName("표 블록이 끝나면(탭 없는 줄) 이름 컬럼의 효력도 끝난다")
		void nameColumnScopeEndsWithTable() {
			// 두 번째 표에는 이름 헤더가 없으므로 같은 위치의 값이 이름으로 오탐되지 않아야 한다
			MaskingResult result = mask("이름\t부서\n김빛솔\t총무팀\n\n요약 문단입니다\n상품명\t수량\n김치찜\t3");

			assertThat(result.summary()).containsEntry(MaskingType.NAME, 1L);
			assertThat(result.maskedText()).contains("김치찜");
		}

		@Test
		@DisplayName("구분자 없는 16자리 카드번호는 Luhn 체크섬을 통과할 때만 탐지한다")
		void contiguousCardRequiresLuhn() {
			// 5105105105105100은 Luhn 통과(실제 카드 형식), 마지막 자리만 바꾸면 실패
			MaskingResult valid = mask("법인카드 5105105105105100 결제 완료");
			assertThat(valid.summary()).containsEntry(MaskingType.CARD_NUMBER, 1L);

			MaskingResult invalid = mask("주문번호 5105105105105101 조회 부탁");
			assertThat(invalid.detections()
				.stream()
				.filter(detection -> detection.type() == MaskingType.CARD_NUMBER))
				.isEmpty();
		}

		@Test
		@DisplayName("앞 문맥이 제품/코드이면 여권번호 형식이라도 잡지 않는다")
		void passportFormatWithProductContextNotDetected() {
			assertThat(mask("제품 코드 A12345678은 재고가 없습니다").hasDetections()).isFalse();

			MaskingResult passport = mask("여권번호 M12345678 확인 부탁드립니다");
			assertThat(passport.summary()).containsEntry(MaskingType.PASSPORT, 1L);
		}
	}

	@Nested
	@DisplayName("혼합 텍스트와 요약")
	class MixedText {

		@Test
		@DisplayName("여러 유형이 섞인 텍스트에서 유형별 건수를 요약한다")
		void summaryCountsByType() {
			MaskingResult result = mask(
				"김철수 과장님(010-1234-5678, kim.cs@haitai.co.kr) 서버는 10.0.1.15입니다. "
					+ "백업 연락처는 010-9999-8888 입니다.");

			Map<MaskingType, Long> summary = result.summary();
			assertThat(summary).containsEntry(MaskingType.NAME, 1L);
			assertThat(summary).containsEntry(MaskingType.PHONE, 2L);
			assertThat(summary).containsEntry(MaskingType.EMAIL, 1L);
			assertThat(summary).containsEntry(MaskingType.IP, 1L);

			// 마스킹 결과에 원본값이 하나도 남아 있지 않아야 한다
			assertThat(result.maskedText())
				.doesNotContain("김철수", "010-1234-5678", "kim.cs@haitai.co.kr", "10.0.1.15", "010-9999-8888");
		}

		@Test
		@DisplayName("탐지 인덱스는 원문 기준이며 원본값 위치와 정확히 일치한다 (미리보기 하이라이트용)")
		void indicesPointToOriginalText() {
			String text = "김철수 과장님 번호는 010-1234-5678 입니다";
			MaskingResult result = mask(text);

			for (Detection detection : result.detections()) {
				assertThat(text.substring(detection.startIndex(), detection.endIndex()))
					.isEqualTo(detection.originalValue());
			}
		}
	}

	@Nested
	@DisplayName("원복 (restore)")
	class Restore {

		@Test
		@DisplayName("마스킹 → 원복을 거치면 원문이 그대로 복원된다")
		void roundTrip() {
			String text = "김철수 과장님(010-1234-5678)이 192.168.0.1 서버 점검을 요청했습니다";
			MaskingResult masked = mask(text);

			String restored = engine.restore(masked.maskedText(), assigner::resolve);

			assertThat(restored).isEqualTo(text);
		}

		@Test
		@DisplayName("매핑이 없는 토큰은 임의 값으로 바꾸지 않고 그대로 남긴다")
		void unknownTokenLeftAsIs() {
			String restored = engine.restore("[PERSON_999] 님께 전달했습니다", assigner::resolve);

			assertThat(restored).isEqualTo("[PERSON_999] 님께 전달했습니다");
		}

		@Test
		@DisplayName("GPT 응답처럼 토큰이 문장 속에 섞여 있어도 해당 부분만 원복한다")
		void restoreInsideSentence() {
			mask("010-1234-5678");
			String gptResponse = "네, [PHONE_001]로 연락드리겠습니다. 다른 질문 있으신가요?";

			String restored = engine.restore(gptResponse, assigner::resolve);

			assertThat(restored).isEqualTo("네, 010-1234-5678로 연락드리겠습니다. 다른 질문 있으신가요?");
		}
	}

	@Nested
	@DisplayName("추가 수동 마스킹")
	class ManualMasking {

		@Test
		@DisplayName("사용자가 지정한 값의 모든 등장 위치가 같은 토큰으로 치환된다")
		void allOccurrencesMasked() {
			String text = "해태제과 신제품 기획안입니다. 해태제과 내부 검토용.";
			MaskingResult result = engine.maskManually(text, "해태제과", MaskingType.CUSTOM, assigner);

			assertThat(result.detections()).hasSize(2);
			assertThat(result.detections().get(0).token()).isEqualTo(result.detections().get(1).token());
			assertThat(result.maskedText()).doesNotContain("해태제과");
		}

		@Test
		@DisplayName("정규식 특수문자가 포함된 값도 리터럴로 안전하게 처리된다")
		void specialCharactersTreatedLiterally() {
			MaskingResult result = engine.maskManually(
				"(주)해태 납품 단가표", "(주)해태", MaskingType.CUSTOM, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.maskedText()).doesNotContain("(주)해태");
		}
	}

	@Nested
	@DisplayName("계좌 / 여권 / 운전면허 / 차량 / 주소 탐지")
	class ExtendedRules {

		@Test
		@DisplayName("은행명이 앞에 붙은 계좌번호를 은행명까지 포함해 탐지한다")
		void accountNumberWithBankName() {
			MaskingResult result = mask("입금 계좌는 카카오뱅크 3333-12-3456789 입니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.ACCOUNT_NUMBER);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("카카오뱅크 3333-12-3456789");
		}

		@Test
		@DisplayName("은행명 없는 숫자 나열은 계좌로 오탐하지 않는다")
		void plainNumbersNotDetectedAsAccount() {
			MaskingResult result = mask("일련번호 110-123-456789 조회");

			assertThat(result.detections()
				.stream()
				.filter(d -> d.type() == MaskingType.ACCOUNT_NUMBER))
				.isEmpty();
		}

		@Test
		@DisplayName("여권번호(영문 1자 + 숫자 8자리)를 탐지한다")
		void passportNumber() {
			MaskingResult result = mask("여권번호 M12345678 확인 부탁드립니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.PASSPORT);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("M12345678");
		}

		@Test
		@DisplayName("운전면허번호는 LICENSE 1건으로만 탐지된다 (내부 숫자열 이중 탐지 없음)")
		void driverLicense() {
			MaskingResult result = mask("면허번호 11-20-123456-78 입니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.DRIVER_LICENSE);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("11-20-123456-78");
		}

		@Test
		@DisplayName("차량번호(2~3자리 + 한글 + 4자리)를 탐지한다")
		void vehicleNumber() {
			MaskingResult result = mask("차량번호 12가 3456으로 접수되었습니다");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.VEHICLE_NUMBER);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("12가 3456");
		}

		@Test
		@DisplayName("도로명 주소를 상세 동/호수까지 통째로 탐지한다")
		void roadAddress() {
			MaskingResult result = mask(
				"주소는 서울특별시 강남구 테헤란로 123 101동 1203호입니다.");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.ADDRESS);
			assertThat(result.detections().get(0).originalValue())
				.isEqualTo("서울특별시 강남구 테헤란로 123 101동 1203호");
		}

		@Test
		@DisplayName("아멕스(4-6-5)·다이너스(4-4-4-2) 형식 카드번호도 탐지한다")
		void nonStandardCardFormats() {
			assertThat(mask("3714-496353-98431").detections())
				.singleElement()
				.satisfies(d -> assertThat(d.type()).isEqualTo(MaskingType.CARD_NUMBER));
			assertThat(mask("3000-0000-0000-04").detections())
				.singleElement()
				.satisfies(d -> assertThat(d.type()).isEqualTo(MaskingType.CARD_NUMBER));
		}

		@Test
		@DisplayName("점(.) 구분자 휴대폰 번호도 탐지한다")
		void mobileWithDotSeparator() {
			MaskingResult result = mask("연락처: 010.9876.5432");

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("010.9876.5432");
		}
	}

	@Nested
	@DisplayName("전파 마스킹 (탐지된 값의 재등장 처리)")
	class Propagation {

		@Test
		@DisplayName("셀에서 잡힌 이름이 문장 속에 조사와 함께 재등장해도 같은 토큰으로 마스킹된다")
		void detectedNamePropagatesIntoSentence() {
			// "이서연"은 탭 경계의 단독 표기 규칙으로만 잡히고,
			// "이서연의"는 규칙으로는 못 잡지만 전파 마스킹으로 함께 가려져야 한다
			String text = "이서연\t920305-2000000\n이서연의 주민번호는 920305-2000000이고 승인 대기중.";
			MaskingResult result = mask(text);

			assertThat(result.maskedText()).doesNotContain("이서연", "920305-2000000");
			long nameTokens = result.detections().stream()
				.filter(d -> d.type() == MaskingType.NAME)
				.map(Detection::token)
				.distinct()
				.count();
			assertThat(nameTokens).isEqualTo(1);
		}

		@Test
		@DisplayName("전파된 탐지도 인덱스가 원문 위치와 정확히 일치한다")
		void propagatedIndicesPointToOriginalText() {
			String text = "박지훈\t박지훈 문의 접수";
			MaskingResult result = mask(text);

			for (Detection detection : result.detections()) {
				assertThat(text.substring(detection.startIndex(), detection.endIndex()))
					.isEqualTo(detection.originalValue());
			}
		}
	}

	@Nested
	@DisplayName("엑셀 추출 텍스트 통합 탐지 (실전 시나리오)")
	class ExcelRowScenario {

		@Test
		@DisplayName("탭으로 구분된 엑셀 한 행의 모든 민감정보가 남김없이 마스킹된다")
		void everyPiiInExcelRowMasked() {
			// 실제 첨부 테스트 파일(pii_masking_test_data_20rows.xlsx)의 1행과 같은 구조
			String row = "1\t김민준\t010-2345-6789\t02-345-6789\t900101-1000000\t"
				+ "minjun.kim@example.com\t192.168.10.25\t서울특별시 강남구 테헤란로 123 101동 1203호\t"
				+ "4111-1111-1111-1111\t국민 123456-78-901234\tM12345678\t11-20-123456-78\t12가 3456\t"
				+ "김민준 고객님 휴대폰은 010-2345-6789, 이메일은 minjun.kim@example.com, "
				+ "주소는 서울특별시 강남구 테헤란로 123 101동 1203호입니다.";

			MaskingResult result = mask(row);

			assertThat(result.maskedText()).doesNotContain(
				"김민준", "010-2345-6789", "02-345-6789", "900101-1000000",
				"minjun.kim@example.com", "192.168.10.25", "테헤란로", "4111-1111-1111-1111",
				"123456-78-901234", "M12345678", "11-20-123456-78", "12가 3456");
		}

		@Test
		@DisplayName("헤더 행의 '이메일' 같은 컬럼명은 이름으로 오탐하지 않는다")
		void headerWordsNotDetectedAsName() {
			String header = "ID\t이름\t휴대폰\t주민등록번호\t이메일\t주소\t통합문장";
			MaskingResult result = mask(header);

			assertThat(result.hasDetections()).isFalse();
		}
	}

	@Nested
	@DisplayName("업무 민감정보 확장 탐지")
	class BusinessSensitiveData {

		@Test
		@DisplayName("일반 입금 금액은 유지하고 이름만 마스킹한다")
		void generalAmountPreserved() {
			MaskingResult result = mask("김철수 50,000원 입금 확인");

			assertThat(result.summary()).containsEntry(MaskingType.NAME, 1L);
			assertThat(result.summary()).doesNotContainKey(MaskingType.COST_PRICE);
			assertThat(result.summary()).doesNotContainKey(MaskingType.CONTRACT_AMOUNT);
			assertThat(result.maskedText()).contains("50,000원");
		}

		@Test
		@DisplayName("계약/원가/급여/공시 전 실적 문맥의 금액은 마스킹한다")
		void sensitiveBusinessAmountsDetected() {
			MaskingResult result = mask("""
				2분기 영업이익 120억원 예상
				A사 납품단가 48,000원
				계약금액 3억원
				홍길동 연봉 5,000만원
				""");

			assertThat(result.summary())
				.containsEntry(MaskingType.FINANCIAL_RESULT, 1L)
				.containsEntry(MaskingType.COST_PRICE, 1L)
				.containsEntry(MaskingType.CONTRACT_AMOUNT, 1L)
				.containsEntry(MaskingType.HR_COMPENSATION, 1L);
			assertThat(result.maskedText())
				.doesNotContain("영업이익 120억원")
				.doesNotContain("납품단가 48,000원")
				.doesNotContain("계약금액 3억원")
				.doesNotContain("연봉 5,000만원");
		}

		@Test
		@DisplayName("SQL은 전체를 지우지 않고 테이블/컬럼 식별자와 기존 개인정보만 마스킹한다")
		void sqlStructurePreserved() {
			MaskingResult result = mask("""
				SELECT c.customer_name, o.order_amount
				FROM production.customer_master c
				JOIN sales.order_history o ON c.customer_id = o.customer_id
				WHERE c.phone = '010-1234-5678'
				""");

			assertThat(result.summary())
				.containsEntry(MaskingType.SQL_QUERY, 9L)
				.containsEntry(MaskingType.PHONE, 1L);
			assertThat(result.maskedText())
				.contains("SELECT")
				.contains("FROM")
				.contains("JOIN")
				.contains("WHERE")
				.doesNotContain("customer_master")
				.doesNotContain("order_history")
				.doesNotContain("customer_master c")
				.doesNotContain("order_history o")
				.doesNotContain("customer_name")
				.doesNotContain("010-1234-5678");
		}

		@Test
		@DisplayName("SQL 식별자는 무의미한 순번 토큰이 아니라 의미 보존형 토큰으로 마스킹한다")
		void sqlIdentifiersUseSemanticTokens() {
			MaskingResult result = mask("""
				SELECT
				       A.ORDER_ID
				     , B.CUSTOMER_NAME
				     , B.PHONE_NO
				     , B.EMAIL
				FROM ORDER_MASTER A
				JOIN CUSTOMER B ON A.CUSTOMER_ID = B.CUSTOMER_ID
				""");

			assertThat(result.maskedText())
				.contains("O_MAIN.ORDER_ID")
				.contains("CUST_MAIN.CUSTOMER_NAME")
				.contains("CUST_MAIN.CUSTOMER_PHONE")
				.contains("CUST_MAIN.CUSTOMER_EMAIL")
				.contains("FROM T01_ORDER_MAIN O_MAIN")
				.contains("JOIN T02_CUSTOMER CUST_MAIN")
				.doesNotContain("[SQL_")
				.doesNotContain("ORDER_MASTER")
				.doesNotContain("CUSTOMER B")
				.doesNotContain("PHONE_NO");
		}

		@Test
		@DisplayName("SQL 출력 컬럼 별칭도 의미 보존형으로 마스킹한다")
		void sqlOutputAliasesUseSemanticTokens() {
			MaskingResult result = mask("""
				SELECT
				       A.ORDER_ID          AS ORDER_ID
				     , B.CUSTOMER_NAME     AS CUSTOMER_NAME
				     , B.PHONE_NO          AS PHONE_NO
				     , B.EMAIL             AS EMAIL
				     , G.MANAGER_NAME      AS SALES_MANAGER_NAME
				FROM ORDER_MASTER A
				JOIN CUSTOMER B ON A.CUSTOMER_ID = B.CUSTOMER_ID
				JOIN EMPLOYEE G ON G.EMPLOYEE_ID = B.MANAGER_ID
				""");

			assertThat(result.maskedText())
				.contains("O_MAIN.ORDER_ID          AS ORDER_ID")
				.contains("CUST_MAIN.CUSTOMER_NAME     AS CUSTOMER_DISPLAY_NAME")
				.contains("CUST_MAIN.CUSTOMER_PHONE          AS CUSTOMER_PHONE")
				.contains("CUST_MAIN.CUSTOMER_EMAIL             AS CUSTOMER_EMAIL")
				.contains("EMP_MAIN.MANAGER_NAME      AS MANAGER_NAME")
				.contains("JOIN T03_EMPLOYEE EMP_MAIN")
				.doesNotContain("AS CUSTOMER_NAME")
				.doesNotContain("AS PHONE_NO")
				.doesNotContain("AS EMAIL")
				.doesNotContain("AS SALES_MANAGER_NAME")
				.doesNotContain(" G ON");
		}

		@Test
		@DisplayName("SQL MEMBER/USER/ACCOUNT 도메인은 CUSTOMER와 분리해 의미 보존형 토큰을 만든다")
		void sqlMemberUserAccountDomainSeparatedFromCustomer() {
			MaskingResult result = mask("""
				SELECT
				       M.MEMBER_ID
				     , M.PHONE_NO
				     , U.LOGIN_ID
				     , AC.ACCOUNT_STATUS
				     , C.CUSTOMER_ID
				FROM MEMBER_PROFILE M
				JOIN USER_LOGIN U ON U.MEMBER_ID = M.MEMBER_ID
				JOIN ACCOUNT_MASTER AC ON AC.MEMBER_ID = M.MEMBER_ID
				JOIN CUSTOMER C ON C.MEMBER_ID = M.MEMBER_ID
				""");

			assertThat(result.maskedText())
				.contains("FROM T01_USER_PROFILE U_PROFILE")
				.contains("JOIN T02_USER_LOGIN U_LOGIN")
				.contains("JOIN T03_USER_MAIN U_MAIN")
				.contains("JOIN T04_CUSTOMER CUST_MAIN")
				.contains("U_PROFILE.MEMBER_ID")
				.contains("U_PROFILE.USER_PHONE")
				.contains("U_LOGIN.LOGIN_ID")
				.contains("U_MAIN.ACCOUNT_STATUS")
				.contains("CUST_MAIN.CUSTOMER_ID")
				.doesNotContain("T01_CUSTOMER")
				.doesNotContain("CUST_PROFILE")
					.doesNotContain("CUSTOMER_PHONE");
		}

		@Test
		@DisplayName("SQL CTE 선언명과 FROM 참조명을 같은 의미형 토큰으로 마스킹한다")
		void sqlCteNamesUseSameSemanticTokenAsFromReference() {
			MaskingResult result = mask("""
				WITH MEMBER_BASE AS (
				    SELECT M.MEMBER_ID, M.MEMBER_NAME
				      FROM APP_SCHEMA.APP_MEMBER M
				     WHERE M.USE_YN = 'Y'
				),
				PAYMENT_SUMMARY AS (
				    SELECT P.MEMBER_ID, SUM(P.PAYMENT_AMOUNT) AS TOTAL_PAYMENT_AMOUNT
				      FROM BILLING_SCHEMA.PAYMENT_HISTORY P
				     GROUP BY P.MEMBER_ID
				)
				SELECT MB.MEMBER_ID, PS.TOTAL_PAYMENT_AMOUNT
				  FROM MEMBER_BASE MB
				  JOIN PAYMENT_SUMMARY PS ON MB.MEMBER_ID = PS.MEMBER_ID
				""");

			String memberBaseToken = result.detections().stream()
				.filter(detection -> detection.originalValue().equals("MEMBER_BASE"))
				.findFirst()
				.orElseThrow()
				.token();
			String paymentSummaryToken = result.detections().stream()
				.filter(detection -> detection.originalValue().equals("PAYMENT_SUMMARY"))
				.findFirst()
				.orElseThrow()
				.token();

			assertThat(result.detections().stream()
				.filter(detection -> detection.originalValue().equals("MEMBER_BASE")))
				.allSatisfy(detection -> assertThat(detection.token()).isEqualTo(memberBaseToken));
			assertThat(result.detections().stream()
				.filter(detection -> detection.originalValue().equals("PAYMENT_SUMMARY")))
				.allSatisfy(detection -> assertThat(detection.token()).isEqualTo(paymentSummaryToken));
			assertThat(result.maskedText())
				.contains("WITH " + memberBaseToken + " AS")
				.contains("FROM " + memberBaseToken + " U_MAIN")
				.contains("JOIN " + paymentSummaryToken + " PY_REF")
				.doesNotContain("WITH MEMBER_BASE")
				.doesNotContain("FROM MEMBER_BASE MB")
				.doesNotContain("PAYMENT_SUMMARY PS");
		}

		@Test
		@DisplayName("중간에 빈 줄과 주석이 있는 SQL도 하나의 블록으로 계속 탐지한다")
		void sqlBlockContinuesAcrossBlankLinesWhenNextLineLooksSql() {
			MaskingResult result = mask("""
				SELECT MB.MEMBER_ID, PS.TOTAL_PAYMENT_AMOUNT
				  FROM MEMBER_BASE MB
				  JOIN PAYMENT_SUMMARY PS ON MB.MEMBER_ID = PS.MEMBER_ID
				 WHERE 1 = 1

				  /* 최근 로그인 또는 최근 결제 회원만 조회 */
				   AND (
				        PS.LAST_PAYMENT_DATE IS NOT NULL
				     OR MB.LAST_LOGIN_AT IS NOT NULL
				       )

				 ORDER BY PS.TOTAL_PAYMENT_AMOUNT DESC
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains("PS.LAST_PAYMENT_DATE", "MB.LAST_LOGIN_AT", "PS.TOTAL_PAYMENT_AMOUNT");
			assertThat(result.maskedText())
				.doesNotContain("PS.LAST_PAYMENT_DATE")
				.doesNotContain("MB.LAST_LOGIN_AT")
				.doesNotContain("PS.TOTAL_PAYMENT_AMOUNT");
		}

		@Test
		@DisplayName("FROM table AS alias 구문은 출력 컬럼 별칭이 아니라 테이블 별칭으로 분류한다")
		void sqlFromTableAsAliasDetectedAsTableAlias() {
			MaskingResult result = mask("""
				SELECT C.CUSTOMER_ID
				  FROM CUSTOMER AS C
				 WHERE C.USE_YN = 'Y'
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.originalValue().equals("C"))
				.extracting(Detection::ruleName)
				.containsOnly("SQL 테이블 별칭");
			assertThat(result.maskedText())
				.contains("FROM T01_CUSTOMER AS CUST_MAIN")
				.contains("CUST_MAIN.CUSTOMER_ID")
				.doesNotContain("FROM CUSTOMER AS C");
		}

		@Test
		@DisplayName("SQL 의미 보존형 토큰도 GPT 응답에서 원본 식별자로 원복한다")
		void sqlSemanticTokensRestored() {
			mask("""
				SELECT B.PHONE_NO
				FROM CUSTOMER B
				""");

			String restored = engine.restore("CUST_MAIN.CUSTOMER_PHONE 컬럼은 T01_CUSTOMER 테이블에서 확인하세요.",
				assigner::resolve);

			assertThat(restored).contains("B.PHONE_NO", "CUSTOMER");
			assertThat(restored).doesNotContain("CUST_MAIN.CUSTOMER_PHONE", "T01_CUSTOMER");
		}

		@Test
		@DisplayName("SQL 의미 보존형 alias 선언과 컬럼 참조는 원복 후 서로 섞이지 않는다")
		void sqlSemanticAliasDeclarationRestoredConsistently() {
			mask("""
				SELECT M.MEMBER_ID
				FROM APP_SCHEMA.APP_MEMBER M
				WHERE M.USE_YN = 'Y'
				""");

			String restored = engine.restore("""
				FROM T01_USER U_MAIN
				WHERE U_MAIN.USE_YN = 'Y'
				""", assigner::resolve);

			assertThat(restored)
				.contains("FROM APP_SCHEMA.APP_MEMBER M")
				.contains("WHERE M.USE_YN = 'Y'")
				.doesNotContain("APP_SCHEMA.APP_MEMBER U_MAIN")
				.doesNotContain("WHERE U_MAIN.USE_YN");
		}

		@Test
		@DisplayName("같은 도메인의 SQL 별칭은 서로 다른 의미형 별칭으로 치환해 조건 의미를 보존한다")
		void sqlAliasTokensStayUniqueWithinSameDomain() {
			MaskingResult result = mask("""
				SELECT A.ORDER_ID, Y.ORDER_ID, OM.ORDER_NO, D.CATEGORY_NAME, E.DISCOUNT_RATE, BL.CUSTOMER_ID
				FROM ORDER_MASTER A
				JOIN ORDER_HISTORY Y ON Y.ORDER_ID = A.ORDER_ID
				JOIN ORDER_RECENT OM ON OM.ORDER_ID = A.ORDER_ID
				JOIN PRODUCT_CATEGORY D ON D.CATEGORY_ID = A.CATEGORY_ID
				JOIN PRODUCT_DISCOUNT E ON E.PRODUCT_ID = A.PRODUCT_ID
				JOIN CUSTOMER B ON B.CUSTOMER_ID = A.CUSTOMER_ID
				LEFT JOIN CUSTOMER_BLACKLIST BL ON BL.CUSTOMER_ID = B.CUSTOMER_ID
				""");

			assertThat(result.maskedText())
				.contains("O_MAIN.ORDER_ID")
				.contains("O_HIST.ORDER_ID")
				.contains("O_RECENT.ORDER_NO")
				.contains("P_CAT.CATEGORY_NAME")
				.contains("P_DISC.DISCOUNT_RATE")
				.contains("CUST_MAIN.CUSTOMER_ID")
				.contains("CUST_EXCL.CUSTOMER_ID")
				.contains("T01_ORDER_MAIN")
				.contains("T02_ORDER_LOG")
				.contains("T03_ORDER_RECENT")
				.contains("T04_PRODUCT_CATEGORY")
				.contains("T05_PRODUCT_DISCOUNT")
				.contains("T07_CUSTOMER_EXCLUSION")
				.doesNotContain("O2.ORDER_ID = O2.ORDER_ID")
				.doesNotContain("P2")
				.doesNotContain("C2");
		}

		@Test
		@DisplayName("SQL 테이블 별칭 선언과 alias.* 참조를 SQL 블록 안에서만 탐지한다")
		void sqlTableAliasesDetectedInsideSqlBlockOnly() {
			MaskingResult result = mask("""
				SELECT cust.*, ord.ORDER_ID
				FROM CUSTOMER_MASTER cust
				JOIN ORDER_MASTER ord ON cust.CUSTOMER_ID = ord.CUSTOMER_ID
				WHERE ord.USE_YN = 'Y';

				첨부 파일
				- alias.report.xlsx

				이메일
				cust.owner@example.com
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains("cust", "ord", "cust.*", "ord.ORDER_ID", "CUSTOMER_MASTER", "ORDER_MASTER")
				.doesNotContain("alias.report.xlsx", "cust.owner", "example.com");
			assertThat(result.maskedText())
				.doesNotContain("CUSTOMER_MASTER cust")
				.doesNotContain("ORDER_MASTER ord")
				.doesNotContain("cust.*")
				.doesNotContain("cust.owner@example.com");
			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.EMAIL)
				.extracting(Detection::originalValue)
				.contains("cust.owner@example.com");
		}

		@Test
		@DisplayName("엑셀 일반 값의 점 포함 코드명은 SQL 문맥이 없으면 SQL로 탐지하지 않는다")
		void dottedExcelValueDoesNotBecomeSqlWithoutSqlContext() {
			MaskingResult result = mask("""
				항목\t코드\t비고
				주문번호\tA.ORDER_ID\t엑셀 일반 데이터
				고객코드\tB.CUSTOMER_NAME\tSQL 쿼리 아님
				설명\tFROM CUSTOMER\t셀 안의 설명 문구
				설명\tJOIN PRODUCT\t셀 안의 설명 문구
				""");

			assertThat(result.detections())
				.noneSatisfy(detection -> assertThat(detection.type()).isEqualTo(MaskingType.SQL_QUERY));
			assertThat(result.maskedText()).contains("A.ORDER_ID", "B.CUSTOMER_NAME", "FROM CUSTOMER",
				"JOIN PRODUCT");
		}

		@Test
		@DisplayName("SQL 쿼리와 엑셀 추출 텍스트가 함께 있어도 SQL 블록 밖 이메일/파일명은 SQL로 탐지하지 않는다")
		void mixedSqlAndExcelTextScansOnlySqlBlock() {
			MaskingResult result = mask("""
				SELECT
				       A.ORDER_ID AS ORDER_ID
				     , B.CUSTOMER_NAME AS CUSTOMER_NAME
				FROM ORDER_MASTER A
				JOIN CUSTOMER B ON A.CUSTOMER_ID = B.CUSTOMER_ID;

				첨부 파일
				- pii_masking_test_data_20rows.xlsx

				이름\t이메일
				김민준\tminjun.kim@example.com
				이서연\tseoyeon.lee@example.net
				박지훈\tjihoon.park@example.org
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains("A.ORDER_ID", "B.CUSTOMER_NAME", "ORDER_MASTER", "CUSTOMER")
				.doesNotContain("pii_masking_test_data_20rows.xlsx", "minjun.kim", "example.com",
					"seoyeon.lee", "example.net", "jihoon.park", "example.org");
			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.EMAIL)
				.extracting(Detection::originalValue)
				.contains("minjun.kim@example.com", "seoyeon.lee@example.net", "jihoon.park@example.org");
		}

		@Test
		@DisplayName("Oracle 콤마 조인 FROM 목록과 서브쿼리 FROM 테이블을 모두 탐지한다")
		void oracleCommaJoinTablesDetected() {
			MaskingResult result = mask("""
				SELECT
				       A.ORDER_ID                          AS ORDER_ID
				     , A.ORDER_NO                          AS ORDER_NO
				     , TO_CHAR(A.ORDER_DATE, 'YYYY-MM-DD') AS ORDER_DATE
				     , B.CUSTOMER_NAME                     AS CUSTOMER_NAME
				     , C.PRODUCT_NAME                      AS PRODUCT_NAME
				     , A.ORDER_QTY * A.ORDER_PRICE         AS ORDER_AMOUNT
				     , NVL(D.INVOICE_NO, '-')              AS INVOICE_NO
				     , (
				           SELECT COUNT(*)
				             FROM ORDER_CLAIM X
				            WHERE X.ORDER_ID = A.ORDER_ID
				              AND X.USE_YN = 'Y'
				       )                                   AS CLAIM_COUNT
				FROM
				       ORDER_MASTER A
				     , CUSTOMER B
				     , PRODUCT C
				     , SHIPMENT D
				WHERE 1 = 1
				  AND A.CUSTOMER_ID = B.CUSTOMER_ID
				  AND A.PRODUCT_ID  = C.PRODUCT_ID
				  AND A.ORDER_ID    = D.ORDER_ID(+)
				  AND A.ORDER_DATE >= TO_DATE('2026-01-01', 'YYYY-MM-DD')
				ORDER BY A.ORDER_DATE DESC
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains("ORDER_MASTER", "CUSTOMER", "PRODUCT", "SHIPMENT", "ORDER_CLAIM");
			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains("ORDER_CLAIM", "ORDER_MASTER", "CUSTOMER", "PRODUCT", "SHIPMENT");
			assertThat(result.detections())
				.filteredOn(detection -> Set.of("ORDER_CLAIM", "ORDER_MASTER", "CUSTOMER", "PRODUCT", "SHIPMENT")
					.contains(detection.originalValue()))
				.extracting(Detection::ruleName)
				.containsOnly("SQL 테이블명(FROM)");
			assertThat(result.maskedText())
				.doesNotContain("ORDER_MASTER", "CUSTOMER B", "PRODUCT C", "SHIPMENT D", "ORDER_CLAIM");

			String orderIdToken = result.detections().stream()
				.filter(detection -> detection.originalValue().equals("A.ORDER_ID"))
				.findFirst()
				.orElseThrow()
				.token();
			assertThat(result.detections().stream()
				.filter(detection -> detection.originalValue().equals("A.ORDER_ID")))
				.allSatisfy(detection -> assertThat(detection.token()).isEqualTo(orderIdToken));
		}

		@Test
		@DisplayName("DB 객체 DML/DDL/프로시저/시퀀스/DB링크 식별자를 탐지한다")
		void databaseObjectIdentifiersDetected() {
			MaskingResult result = mask("""
				INSERT INTO ORDER_AUDIT (ORDER_ID, CREATED_AT, AUDIT_SEQ)
				VALUES (ORDER_SEQ.NEXTVAL, SYSDATE, AUDIT_SEQ.CURRVAL);
				UPDATE ORDER_MASTER SET ORDER_STATUS = '05', UPDATED_AT = SYSDATE WHERE ORDER_ID = 1;
				DELETE FROM ORDER_TEMP WHERE CREATED_AT < SYSDATE - 7;
				MERGE INTO CUSTOMER_TARGET T USING CUSTOMER_SOURCE S ON (T.CUSTOMER_ID = S.CUSTOMER_ID) WHEN MATCHED THEN UPDATE SET T.NAME = S.NAME;
				TRUNCATE TABLE OLD_ORDER_LOG;
				CREATE TABLE ORDER_BACKUP AS SELECT * FROM ORDER_MASTER@ERP_LINK;
				ALTER INDEX IDX_ORDER_MASTER_01 REBUILD;
				DROP SEQUENCE OLD_ORDER_SEQ;
				CALL PKG_ORDER.RECALC_ORDER(:P_ORDER_ID);
				EXEC PRC_CLOSE_ORDER;
				""");

			assertThat(result.detections())
				.filteredOn(detection -> detection.type() == MaskingType.SQL_QUERY)
				.extracting(Detection::originalValue)
				.contains(
					"ORDER_AUDIT", "ORDER_ID", "CREATED_AT", "AUDIT_SEQ",
					"ORDER_SEQ", "ORDER_MASTER", "ORDER_STATUS", "UPDATED_AT",
					"ORDER_TEMP", "CUSTOMER_TARGET", "CUSTOMER_SOURCE", "OLD_ORDER_LOG",
					"ORDER_BACKUP", "ORDER_MASTER@ERP_LINK", "IDX_ORDER_MASTER_01",
					"OLD_ORDER_SEQ", "PKG_ORDER.RECALC_ORDER", "PRC_CLOSE_ORDER"
				);
			assertThat(result.maskedText())
				.doesNotContain("ORDER_AUDIT", "ORDER_MASTER", "CUSTOMER_TARGET", "ORDER_MASTER@ERP_LINK",
					"PKG_ORDER.RECALC_ORDER", "PRC_CLOSE_ORDER");
		}

		@Test
		@DisplayName("API Key, JWT, JDBC URL 같은 보안 시크릿은 문맥과 무관하게 마스킹한다")
		void securitySecretsAlwaysDetected() {
			MaskingResult result = mask("""
				api_key = sk-test-1234567890abcdef
				token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.VeryLongSignature123
				jdbc:mysql://db-prod.internal:3306/customer?user=app&password=secret123
				""");

			assertThat(result.summary()).containsEntry(MaskingType.SECURITY_SECRET, 3L);
			assertThat(result.maskedText())
				.doesNotContain("sk-test-1234567890abcdef")
				.doesNotContain("eyJhbGciOiJIUzI1NiJ9")
				.doesNotContain("jdbc:mysql://");
		}

		@Test
		@DisplayName("내부 문서번호와 법무 식별 정보는 업무 민감정보로 탐지한다")
		void internalDocumentAndLegalDetected() {
			MaskingResult result = mask("""
				품의번호 HT-2026-001
				사건번호 2024가단12345
				법무법인 태평양 검토
				""");

			assertThat(result.summary())
				.containsEntry(MaskingType.INTERNAL_DOC_ID, 1L)
				.containsEntry(MaskingType.LEGAL_DOCUMENT, 2L);
			assertThat(result.maskedText())
				.doesNotContain("HT-2026-001")
				.doesNotContain("2024가단12345")
				.doesNotContain("법무법인 태평양");
		}
	}

	@Nested
	@DisplayName("규칙 오류 내성")
	class BrokenRule {

		@Test
		@DisplayName("정규식이 깨진 규칙은 건너뛰고 나머지 규칙은 정상 적용된다")
		void brokenRegexSkipped() {
			List<MaskingRule> withBroken = List.of(
				MaskingRule.create("깨진 규칙", MaskingType.CUSTOM, "([", 1, "컴파일 불가 패턴"),
				MaskingRule.create("휴대폰", MaskingType.PHONE,
					"(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)", 2, null)
			);

			MaskingResult result = engine.mask("010-1234-5678", withBroken, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.PHONE);
		}
	}

	/**
	 * Redis 대신 사용하는 인메모리 토큰 저장소입니다.
	 * 운영 구현(RedisTokenMappingStore)과 동일하게
	 * "같은 (type, value) → 같은 토큰, 타입별 순번 채번" 규칙을 따릅니다.
	 */
	private static class InMemoryTokenAssigner implements TokenAssigner {

		private final Map<String, String> valueToToken = new HashMap<>();
		private final Map<String, String> tokenToValue = new HashMap<>();
		private final Map<MaskingType, Integer> sequences = new EnumMap<>(MaskingType.class);

		@Override
		public String assign(MaskingType type, String value) {
			return valueToToken.computeIfAbsent(type.name() + ":" + value, key -> {
				int sequence = sequences.merge(type, 1, Integer::sum);
				String token = "[" + type.getTokenLabel() + "_" + String.format("%03d", sequence) + "]";
				tokenToValue.put(token, value);
				return token;
			});
		}

		@Override
		public void remember(MaskingType type, String value, String token) {
			valueToToken.putIfAbsent(type.name() + ":" + value, token);
			tokenToValue.putIfAbsent(token, value);
		}

		/** 원복 테스트용 역방향 조회 (TokenResolver로 사용) */
		String resolve(String token) {
			return tokenToValue.get(token);
		}
	}
}
