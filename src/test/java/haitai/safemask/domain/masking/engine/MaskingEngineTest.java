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

	@Nested
	@DisplayName("탐지가 없는 일상 대화")
	class NoDetection {

		@Test
		@DisplayName("민감정보가 없으면 원문이 그대로 유지되고 탐지 내역이 비어 있다")
		void plainChat() {
			MaskingResult result = engine.mask("안녕! 오늘 회의 자료 정리 좀 도와줘.", rules, assigner);

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
			MaskingResult result = engine.mask("제 번호는 010-1234-5678 입니다.", rules, assigner);

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
			MaskingResult result = engine.mask("01012345678로 연락주세요", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("01012345678");
		}

		@Test
		@DisplayName("휴대폰/유선 두 규칙에 모두 걸리는 번호도 우선순위 규칙 하나로만 탐지된다")
		void noDoubleDetectionByOverlappingRules() {
			// 010-1234-5678은 휴대폰(30)과 유선전화(31) 패턴 모두에 매칭되지만,
			// 먼저 적용된 휴대폰 규칙이 구간을 선점하므로 탐지는 1건이어야 한다.
			MaskingResult result = engine.mask("010-1234-5678", rules, assigner);

			assertThat(result.detections()).hasSize(1);
		}

		@Test
		@DisplayName("같은 번호가 여러 번 나오면 항상 같은 토큰으로 치환된다")
		void sameValueSameToken() {
			MaskingResult result = engine.mask(
				"010-1234-5678로 걸었는데 안 받아서 010-1234-5678로 문자 남겼어", rules, assigner);

			assertThat(result.detections()).hasSize(2);
			assertThat(result.detections().get(0).token()).isEqualTo(result.detections().get(1).token());
			assertThat(result.summary()).containsEntry(MaskingType.PHONE, 2L);
		}

		@Test
		@DisplayName("다른 번호에는 다른 토큰이 발급된다")
		void differentValueDifferentToken() {
			MaskingResult result = engine.mask("010-1111-2222랑 010-3333-4444", rules, assigner);

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
			MaskingResult result = engine.mask("주민번호는 990101-1234567 입니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.RRN);
			assertThat(result.maskedText()).doesNotContain("990101-1234567");
		}

		@Test
		@DisplayName("성별 코드가 1~4 범위 밖이면 주민번호로 보지 않는다")
		void invalidGenderCodeNotDetected() {
			MaskingResult result = engine.mask("코드값 990101-9234567 확인", rules, assigner);

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
			MaskingResult result = engine.mask("회신은 hong.wk@haitai.co.kr로 부탁드립니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.EMAIL);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("hong.wk@haitai.co.kr");
		}

		@Test
		@DisplayName("IPv4 주소를 탐지한다")
		void ipv4() {
			MaskingResult result = engine.mask("운영 서버 192.168.10.25 에 배포했습니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.IP);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("192.168.10.25");
		}

		@Test
		@DisplayName("버전 문자열(세 자리)이나 옥텟 범위를 벗어난 숫자는 IP로 오탐하지 않는다")
		void ipFalsePositives() {
			assertThat(engine.mask("스프링부트 4.1.0으로 올렸어요", rules, assigner).hasDetections()).isFalse();
			assertThat(engine.mask("일련번호 1234.5.6.7 조회", rules, assigner).hasDetections()).isFalse();
		}

		@Test
		@DisplayName("구분자가 일관된 16자리 카드번호를 탐지한다")
		void cardNumber() {
			MaskingResult result = engine.mask("법인카드 1234-5678-9012-3456 결제분입니다", rules, assigner);

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
			MaskingResult result = engine.mask("김철수 과장님께 보고했습니다", rules, assigner);

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
			MaskingResult result = engine.mask("홍왕기님이 요청한 자료입니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("홍왕기");
		}

		@Test
		@DisplayName("'이사님' 같은 직급 단어를 이름으로 오탐하지 않는다")
		void titleWordNotDetectedAsName() {
			MaskingResult result = engine.mask("이사님과 회의가 있습니다", rules, assigner);

			assertThat(result.hasDetections()).isFalse();
		}
	}

	@Nested
	@DisplayName("혼합 텍스트와 요약")
	class MixedText {

		@Test
		@DisplayName("여러 유형이 섞인 텍스트에서 유형별 건수를 요약한다")
		void summaryCountsByType() {
			MaskingResult result = engine.mask(
				"김철수 과장님(010-1234-5678, kim.cs@haitai.co.kr) 서버는 10.0.1.15입니다. "
					+ "백업 연락처는 010-9999-8888 입니다.",
				rules, assigner);

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
			MaskingResult result = engine.mask(text, rules, assigner);

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
			MaskingResult masked = engine.mask(text, rules, assigner);

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
			engine.mask("010-1234-5678", rules, assigner);
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
			MaskingResult result = engine.mask("입금 계좌는 카카오뱅크 3333-12-3456789 입니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.ACCOUNT_NUMBER);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("카카오뱅크 3333-12-3456789");
		}

		@Test
		@DisplayName("은행명 없는 숫자 나열은 계좌로 오탐하지 않는다")
		void plainNumbersNotDetectedAsAccount() {
			MaskingResult result = engine.mask("일련번호 110-123-456789 조회", rules, assigner);

			assertThat(result.detections()
				.stream()
				.filter(d -> d.type() == MaskingType.ACCOUNT_NUMBER))
				.isEmpty();
		}

		@Test
		@DisplayName("여권번호(영문 1자 + 숫자 8자리)를 탐지한다")
		void passportNumber() {
			MaskingResult result = engine.mask("여권번호 M12345678 확인 부탁드립니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.PASSPORT);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("M12345678");
		}

		@Test
		@DisplayName("운전면허번호는 LICENSE 1건으로만 탐지된다 (내부 숫자열 이중 탐지 없음)")
		void driverLicense() {
			MaskingResult result = engine.mask("면허번호 11-20-123456-78 입니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.DRIVER_LICENSE);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("11-20-123456-78");
		}

		@Test
		@DisplayName("차량번호(2~3자리 + 한글 + 4자리)를 탐지한다")
		void vehicleNumber() {
			MaskingResult result = engine.mask("차량번호 12가 3456으로 접수되었습니다", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.VEHICLE_NUMBER);
			assertThat(result.detections().get(0).originalValue()).isEqualTo("12가 3456");
		}

		@Test
		@DisplayName("도로명 주소를 상세 동/호수까지 통째로 탐지한다")
		void roadAddress() {
			MaskingResult result = engine.mask(
				"주소는 서울특별시 강남구 테헤란로 123 101동 1203호입니다.", rules, assigner);

			assertThat(result.detections()).hasSize(1);
			assertThat(result.detections().get(0).type()).isEqualTo(MaskingType.ADDRESS);
			assertThat(result.detections().get(0).originalValue())
				.isEqualTo("서울특별시 강남구 테헤란로 123 101동 1203호");
		}

		@Test
		@DisplayName("아멕스(4-6-5)·다이너스(4-4-4-2) 형식 카드번호도 탐지한다")
		void nonStandardCardFormats() {
			assertThat(engine.mask("3714-496353-98431", rules, assigner).detections())
				.singleElement()
				.satisfies(d -> assertThat(d.type()).isEqualTo(MaskingType.CARD_NUMBER));
			assertThat(engine.mask("3000-0000-0000-04", rules, assigner).detections())
				.singleElement()
				.satisfies(d -> assertThat(d.type()).isEqualTo(MaskingType.CARD_NUMBER));
		}

		@Test
		@DisplayName("점(.) 구분자 휴대폰 번호도 탐지한다")
		void mobileWithDotSeparator() {
			MaskingResult result = engine.mask("연락처: 010.9876.5432", rules, assigner);

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
			MaskingResult result = engine.mask(text, rules, assigner);

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
			MaskingResult result = engine.mask(text, rules, assigner);

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

			MaskingResult result = engine.mask(row, rules, assigner);

			assertThat(result.maskedText()).doesNotContain(
				"김민준", "010-2345-6789", "02-345-6789", "900101-1000000",
				"minjun.kim@example.com", "192.168.10.25", "테헤란로", "4111-1111-1111-1111",
				"123456-78-901234", "M12345678", "11-20-123456-78", "12가 3456");
		}

		@Test
		@DisplayName("헤더 행의 '이메일' 같은 컬럼명은 이름으로 오탐하지 않는다")
		void headerWordsNotDetectedAsName() {
			String header = "ID\t이름\t휴대폰\t주민등록번호\t이메일\t주소\t통합문장";
			MaskingResult result = engine.mask(header, rules, assigner);

			assertThat(result.hasDetections()).isFalse();
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

		/** 원복 테스트용 역방향 조회 (TokenResolver로 사용) */
		String resolve(String token) {
			return tokenToValue.get(token);
		}
	}
}
