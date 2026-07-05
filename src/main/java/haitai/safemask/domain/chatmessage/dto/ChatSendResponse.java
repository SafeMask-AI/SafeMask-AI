package haitai.safemask.domain.chatmessage.dto;

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
	List<MaskingDetectionResponse> detections
) {
	public static ChatSendResponse preview(Long chatRoomId, String maskedPreview,
		Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections) {
		return new ChatSendResponse(chatRoomId, null, null, true, null, maskedPreview, summary, detections);
	}

	public static ChatSendResponse completed(Long chatRoomId, Long userMessageId, Long assistantMessageId,
		String assistantContent, Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections) {
		return new ChatSendResponse(chatRoomId, userMessageId, assistantMessageId, false, assistantContent,
			null, summary, detections);
	}
}
