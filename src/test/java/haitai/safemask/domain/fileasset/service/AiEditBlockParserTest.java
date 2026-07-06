package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.fileasset.service.AiEditBlockParser.EditBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 편집 지시 블록 파서 단위 테스트입니다.
 */
class AiEditBlockParserTest {

	private final AiEditBlockParser parser = new AiEditBlockParser(new ObjectMapper());

	@Test
	@DisplayName("편집 블록에서 대상 파일명과 지시 JSON을 파싱한다")
	void parseEditBlock() {
		String answer = """
			주민등록번호 컬럼을 삭제했습니다.

			[[SAFEMASK_EDIT file="고객명단.xlsx"]]
			{"result": "고객명단_정리.xlsx", "ops": [
			  {"op": "delete_column", "column": "주민등록번호"},
			  {"op": "sort", "column": "이름", "order": "desc"}
			]}
			[[/SAFEMASK_EDIT]]""";

		List<EditBlock> blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		EditBlock block = blocks.get(0);
		assertThat(block.targetFileName()).isEqualTo("고객명단.xlsx");
		assertThat(block.instruction().result()).isEqualTo("고객명단_정리.xlsx");
		assertThat(block.instruction().ops()).hasSize(2);
		assertThat(block.instruction().ops().get(0).op()).isEqualTo("delete_column");
		assertThat(block.instruction().ops().get(1).order()).isEqualTo("desc");
	}

	@Test
	@DisplayName("JSON이 깨진 블록은 instruction=null로 반환해 블록 단위 실패 처리를 가능하게 한다")
	void brokenJsonReturnsNullInstruction() {
		String answer = """
			[[SAFEMASK_EDIT file="a.xlsx"]]
			{이건 JSON이 아님}
			[[/SAFEMASK_EDIT]]""";

		List<EditBlock> blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).instruction()).isNull();
	}

	@Test
	@DisplayName("코드펜스로 감싼 편집 블록도 펜스까지 치환 범위에 포함한다")
	void codeFenceIncludedInRange() {
		String answer = """
			결과:

			```json
			[[SAFEMASK_EDIT file="a.xlsx"]]
			{"ops": [{"op": "rename_column", "from": "이름", "to": "성명"}]}
			[[/SAFEMASK_EDIT]]
			```
			끝.""";

		List<EditBlock> blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		String replaced = answer.substring(0, blocks.get(0).start()) + "(치환)"
			+ answer.substring(blocks.get(0).end());
		assertThat(replaced).doesNotContain("```", "SAFEMASK_EDIT");
	}

	@Test
	@DisplayName("편집 블록이 없는 응답은 빈 리스트를 반환한다")
	void noBlocks() {
		assertThat(parser.parse("일반 답변입니다.")).isEmpty();
	}
}
