package haitai.safemask.domain.chatmessage.approval;

import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.masking.dto.MaskingResult;
import java.util.List;

/**
 * 사용자가 확인한 뒤 승인할 수 있는 서버 측 스냅샷입니다.
 *
 * <p>원문과 마스킹 결과는 짧은 TTL 동안 Redis에만 보관하며, 실제 AI 호출은 이 스냅샷의
 * maskedText만 사용합니다. 첨부 바이트는 보관하지 않고 SHA-256 지문으로 동일성을 검증합니다.
 */
public record MaskingApprovalSnapshot(
	Long memberId,
	Long chatRoomId,
	boolean temporaryChatRoom,
	String displayContent,
	MaskingResult maskingResult,
	List<ManualMaskRequest> manualMasks,
	List<ApprovedFileFingerprint> files
) {
	public MaskingApprovalSnapshot {
		manualMasks = manualMasks == null ? List.of() : manualMasks.stream()
			.filter(mask -> mask != null)
			.toList();
		files = files == null ? List.of() : List.copyOf(files);
	}
}
