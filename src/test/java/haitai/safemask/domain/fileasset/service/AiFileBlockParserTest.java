package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;

import haitai.safemask.domain.fileasset.service.AiFileBlockParser.FileBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 응답 파일 블록 파서 단위 테스트입니다.
 * GPT가 실제로 내놓는 다양한 변형(코드펜스로 감싼 블록, 따옴표 CSV 등)을 검증합니다.
 */
class AiFileBlockParserTest {

	private final AiFileBlockParser parser = new AiFileBlockParser();

	@Test
	@DisplayName("응답 속 파일 블록에서 파일명과 본문을 추출한다")
	void parseBasicBlock() {
		String answer = """
			정리한 파일입니다.

			[[SAFEMASK_FILE name="정리본.xlsx"]]
			이름,휴대폰
			김민준,010-2345-6789
			[[/SAFEMASK_FILE]]

			확인해 주세요.""";

		List<FileBlock> blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).fileName()).isEqualTo("정리본.xlsx");
		assertThat(blocks.get(0).body()).isEqualTo("이름,휴대폰\n김민준,010-2345-6789");
	}

	@Test
	@DisplayName("GPT가 블록을 마크다운 코드펜스로 감싸도 펜스까지 블록 범위로 잡는다")
	void parseBlockWrappedInCodeFence() {
		String answer = """
			결과입니다.

			```
			[[SAFEMASK_FILE name="목록.csv"]]
			a,b
			[[/SAFEMASK_FILE]]
			```
			끝.""";

		List<FileBlock> blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		// 블록 구간을 안내 문구로 치환했을 때 응답에 ``` 찌꺼기가 남으면 안 된다
		String replaced = answer.substring(0, blocks.get(0).start()) + "(치환)"
			+ answer.substring(blocks.get(0).end());
		assertThat(replaced).doesNotContain("```", "SAFEMASK_FILE");
	}

	@Test
	@DisplayName("블록이 여러 개면 등장 순서대로 모두 찾는다")
	void parseMultipleBlocks() {
		String answer = """
			[[SAFEMASK_FILE name="a.xlsx"]]
			x
			[[/SAFEMASK_FILE]]
			중간 설명
			[[SAFEMASK_FILE name="b.csv"]]
			y
			[[/SAFEMASK_FILE]]""";

		List<FileBlock> blocks = parser.parse(answer);

		assertThat(blocks).extracting(FileBlock::fileName).containsExactly("a.xlsx", "b.csv");
	}

	@Test
	@DisplayName("블록이 없는 일반 응답은 빈 리스트를 반환한다")
	void noBlocks() {
		assertThat(parser.parse("일반적인 답변입니다.")).isEmpty();
	}

	@Test
	@DisplayName("따옴표로 감싼 셀 안의 쉼표·줄바꿈·이스케이프 따옴표를 올바르게 파싱한다")
	void parseCsvWithQuotes() {
		String csv = "이름,메모\n김민준,\"서울, 강남 거주\n\"\"VIP\"\" 고객\"";

		List<List<String>> rows = parser.parseCsv(csv);

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsExactly("이름", "메모");
		assertThat(rows.get(1)).containsExactly("김민준", "서울, 강남 거주\n\"VIP\" 고객");
	}

	@Test
	@DisplayName("본문 끝의 빈 줄은 빈 행으로 만들지 않는다")
	void trailingBlankLinesIgnored() {
		List<List<String>> rows = parser.parseCsv("a,b\n1,2\n\n");

		assertThat(rows).hasSize(2);
	}
}
