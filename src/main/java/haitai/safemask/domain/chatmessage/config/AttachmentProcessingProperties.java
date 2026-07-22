package haitai.safemask.domain.chatmessage.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * AI에 전달하기 위해 첨부파일에서 추출하는 콘텐츠의 서버 보호 한도입니다.
 *
 * <p>업로드 파일의 바이트 크기만으로는 실제 AI 입력량을 알 수 없습니다. 특히 압축 형식인
 * xlsx/docx는 작은 파일에서도 많은 문자열이 나올 수 있으므로, 파싱 단계의 셀·페이지 수와
 * 추출 후 문자 수를 별도로 제한합니다. 한도 초과 내용을 조용히 잘라내면 민감정보나 업무 행이
 * 누락된 채 AI가 파일 전체를 읽은 것처럼 답할 수 있어 반드시 요청을 명시적으로 거절합니다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "safemask.attachment")
public class AttachmentProcessingProperties {

	@Min(1)
	private int maxExtractedCharsPerFile = 120_000;

	@Min(1)
	private int maxExtractedCharsPerRequest = 180_000;

	@Min(1)
	private int maxExcelCells = 200_000;

	@Min(1)
	private int maxPdfPages = 300;
}
