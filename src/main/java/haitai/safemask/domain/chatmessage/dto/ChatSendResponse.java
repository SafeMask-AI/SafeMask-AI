package haitai.safemask.domain.chatmessage.dto;

import haitai.safemask.domain.fileasset.dto.GeneratedFileResponse;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import java.util.List;
import java.util.Map;

public record ChatSendResponse(
	Long chatRoomId,
	Long userMessageId,
	Long assistantMessageId,
	boolean previewRequired,
	String assistantContent,
	String maskedPreview,
	Map<MaskingType, Long> summary,
	List<MaskingDetectionResponse> detections,
	/** AI가 이번 응답에서 생성한 다운로드 파일 목록 (없으면 빈 리스트) */
	List<GeneratedFileResponse> generatedFiles
) {
	public static ChatSendResponse preview(Long chatRoomId, String maskedPreview,
		Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections) {
		return new ChatSendResponse(chatRoomId, null, null, true, null, maskedPreview, summary, detections,
			List.of());
	}

	public static ChatSendResponse completed(Long chatRoomId, Long userMessageId, Long assistantMessageId,
		String assistantContent, Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections,
		List<GeneratedFileResponse> generatedFiles) {
		return new ChatSendResponse(chatRoomId, userMessageId, assistantMessageId, false, assistantContent,
			null, summary, detections, generatedFiles);
	}
}
