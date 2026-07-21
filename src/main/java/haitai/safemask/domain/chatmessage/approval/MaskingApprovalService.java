package haitai.safemask.domain.chatmessage.approval;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.masking.config.MaskingProperties;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 승인 스냅샷 발급, 첨부 동일성 확인, 원자적 1회 소비를 담당합니다. */
@Service
@RequiredArgsConstructor
public class MaskingApprovalService {

	private final MaskingApprovalStore approvalStore;
	private final FileFingerprintService fileFingerprintService;
	private final MaskingProperties maskingProperties;

	public IssuedMaskingApproval issue(Member member, ChatRoom chatRoom, boolean temporaryChatRoom,
		String displayContent, MaskingResult maskingResult, List<ManualMaskRequest> manualMasks,
		List<MultipartFile> files) {

		MaskingApprovalSnapshot snapshot = new MaskingApprovalSnapshot(
			member.getId(), chatRoom.getId(), temporaryChatRoom, displayContent, maskingResult,
			manualMasks, fileFingerprintService.fingerprint(files));
		String approvalId = approvalStore.save(snapshot, maskingProperties.getApprovalTtl());
		return new IssuedMaskingApproval(approvalId, snapshot);
	}

	public MaskingApprovalSnapshot consume(Member member, String approvalId, List<MultipartFile> files) {
		MaskingApprovalSnapshot pending = approvalStore.find(member.getId(), approvalId)
			.orElseThrow(() -> new CustomException(ErrorCode.MASKING_APPROVAL_INVALID));

		List<ApprovedFileFingerprint> actualFiles = fileFingerprintService.fingerprint(files);
		if (!pending.files().equals(actualFiles)) {
			throw new CustomException(ErrorCode.MASKING_APPROVAL_FILE_CHANGED);
		}

		return approvalStore.consume(member.getId(), approvalId)
			.orElseThrow(() -> new CustomException(ErrorCode.MASKING_APPROVAL_INVALID));
	}

	public void discard(Member member, String approvalId) {
		if (member != null && approvalId != null && !approvalId.isBlank()) {
			approvalStore.delete(member.getId(), approvalId);
		}
	}

	public void discardForRoom(Member member, Long chatRoomId) {
		if (member != null && chatRoomId != null) {
			approvalStore.deleteForRoom(member.getId(), chatRoomId);
		}
	}
}
