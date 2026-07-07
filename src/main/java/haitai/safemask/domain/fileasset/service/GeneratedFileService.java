package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.fileasset.dto.GeneratedFileResponse;
import haitai.safemask.domain.fileasset.entity.FileAsset;
import haitai.safemask.domain.fileasset.enums.FileAssetStatus;
import haitai.safemask.domain.fileasset.repository.FileAssetRepository;
import haitai.safemask.domain.fileasset.service.AiEditBlockParser.EditBlock;
import haitai.safemask.domain.fileasset.service.AiFileBlockParser.FileBlock;
import haitai.safemask.domain.fileasset.service.ExcelEditExecutor.UnsupportedEditException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 응답 속 파일 블록을 실제 다운로드 가능한 파일로 만들어주는 서비스입니다.
 * 두 종류의 블록을 처리합니다.
 * <ul>
 *   <li>[[SAFEMASK_FILE]] — 새 파일 생성: CSV 본문을 xlsx/csv/txt/md로 변환</li>
 *   <li>[[SAFEMASK_EDIT]] — 업로드 원본 수정: 원본의 "카피"에 편집 지시를 적용해
 *       서식이 보존된 결과 파일 생성 (원본은 절대 변경되지 않음)</li>
 * </ul>
 *
 * <p>처리 순서 (반드시 토큰 원복이 끝난 응답을 입력으로 받아야 합니다):
 * 블록 파싱 → 파일 생성/편집 → 스토리지 저장 + FileAsset(GENERATED) 기록 →
 * 응답 본문의 블록을 "생성된 파일" 안내 문구로 치환.
 *
 * <p>보안: 파일에는 원복된 원본 민감정보가 들어갑니다. 파일은 사내 스토리지에만 존재하고
 * 다운로드는 채팅방 소유자 인증을 거치므로, "GPT에는 마스킹본만, 사용자에게는 원본"
 * 원칙이 파일에도 동일하게 적용됩니다.
 *
 * <p>블록 하나가 실패해도 답변 전체를 실패시키지 않고 해당 블록만
 * 실패 안내로 치환합니다. (텍스트 답변은 이미 유효하기 때문)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedFileService {

	/** 허용 확장자. 목록 밖 확장자(exe 등)를 모델이 지정해도 xlsx로 강제됩니다. */
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xlsx", "csv", "txt", "md");

	/** 표 데이터가 아닌 본문을 그대로 저장하는 텍스트 계열 확장자 */
	private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

	/** 응답 하나에서 만들 수 있는 최대 파일 수 (모델 오동작으로 인한 대량 생성 방지) */
	private static final int MAX_FILES_PER_ANSWER = 5;

	private final AiFileBlockParser aiFileBlockParser;
	private final AiEditBlockParser aiEditBlockParser;
	private final ExcelFileWriter excelFileWriter;
	private final ExcelEditExecutor excelEditExecutor;
	private final FileStorageService fileStorageService;
	private final FileAssetRepository fileAssetRepository;

	/**
	 * 파일 블록 처리 결과.
	 *
	 * @param displayContent 블록이 안내 문구로 치환된, 화면·DB 저장용 응답 본문
	 * @param files          생성된 파일들의 다운로드 정보 (없으면 빈 리스트)
	 */
	public record Outcome(String displayContent, List<GeneratedFileResponse> files) {
	}

	/** 본문에서 치환할 블록 구간과, 실행 시 파일을 만들고 안내 문구를 돌려주는 작업 */
	private record PendingBlock(int start, int end, Supplier<String> builder) {
	}

	/**
	 * 원복된 AI 응답에서 파일 생성·편집 블록을 찾아 실제 파일로 만듭니다.
	 * 블록이 없으면 응답을 그대로 돌려줍니다.
	 */
	public Outcome materialize(ChatRoom chatRoom, String restoredAnswer) {
		List<GeneratedFileResponse> files = new ArrayList<>();

		List<PendingBlock> pending = new ArrayList<>();
		for (FileBlock block : aiFileBlockParser.parse(restoredAnswer)) {
			pending.add(new PendingBlock(block.start(), block.end(), () -> buildFile(chatRoom, block, files)));
		}
		for (EditBlock block : aiEditBlockParser.parse(restoredAnswer)) {
			pending.add(new PendingBlock(block.start(), block.end(), () -> buildEditedFile(chatRoom, block, files)));
		}
		if (pending.isEmpty()) {
			return new Outcome(restoredAnswer, List.of());
		}

		// 등장 순서대로 파일을 만들어 안내 문구를 확정
		pending.sort(Comparator.comparingInt(PendingBlock::start));
		List<String> notices = new ArrayList<>();
		for (PendingBlock block : pending) {
			if (files.size() >= MAX_FILES_PER_ANSWER) {
				notices.add("(파일 생성 한도를 초과해 이 파일은 만들지 않았습니다)");
			} else {
				notices.add(block.builder().get());
			}
		}

		// 뒤 블록부터 치환해야 앞 블록의 인덱스가 밀리지 않음 (MaskingEngine과 동일한 방식)
		StringBuilder content = new StringBuilder(restoredAnswer);
		for (int i = pending.size() - 1; i >= 0; i--) {
			content.replace(pending.get(i).start(), pending.get(i).end(), notices.get(i));
		}

		return new Outcome(content.toString(), List.copyOf(files));
	}

	/**
	 * 생성 블록 하나를 파일로 만들고, 본문에 남길 안내 문구를 반환합니다.
	 * 안내 문구의 "(파일번호 N)" 표기는 프런트가 과거 대화를 다시 열었을 때
	 * 다운로드 버튼을 복원하는 근거가 되므로 형식을 바꾸면 chat.js도 함께 바꿔야 합니다.
	 */
	private String buildFile(ChatRoom chatRoom, FileBlock block, List<GeneratedFileResponse> files) {
		try {
			String fileName = sanitizeFileName(block.fileName());
			String extension = extensionOf(fileName);
			byte[] bytes = toBytes(block, extension);
			return saveGenerated(chatRoom, fileName, extension, bytes, files);
		} catch (RuntimeException e) {
			// 파일 하나가 실패해도 텍스트 답변은 살린다. 원인은 서버 로그로 추적.
			log.error("AI 생성 파일 변환에 실패했습니다. chatRoomId={}, fileName={}",
				chatRoom.getId(), block.fileName(), e);
			return "(파일 생성에 실패했습니다: " + block.fileName() + ")";
		}
	}

	/**
	 * 편집 블록 하나를 처리합니다: 대상 업로드 원본을 찾아 카피에 지시를 적용하고
	 * 서식이 보존된 결과 파일을 저장합니다.
	 */
	private String buildEditedFile(ChatRoom chatRoom, EditBlock block, List<GeneratedFileResponse> files) {
		if (block.instruction() == null) {
			return "(파일 수정 지시를 해석하지 못했습니다. 다시 요청해 주세요)";
		}

		// 원본 조회부터 try 안에 두어, 조회 실패(DB 오류 등)도 블록 단위 실패로 격리한다
		try {
			Optional<FileAsset> original = findOriginal(chatRoom, block.targetFileName());
			if (original.isEmpty()) {
				return "(수정할 원본 파일을 찾지 못했습니다: " + block.targetFileName() + " — 파일을 다시 첨부해 주세요)";
			}
			if (!"xlsx".equals(extensionOf(original.get().getOriginalName()))) {
				return "(엑셀(xlsx) 파일만 서식 보존 수정을 지원합니다: " + original.get().getOriginalName() + ")";
			}

			byte[] originalBytes = fileStorageService.load(original.get().getStoredPath());
			byte[] edited = excelEditExecutor.apply(originalBytes, block.instruction());

			String resultName = sanitizeFileName(resolveResultName(block, original.get().getOriginalName()));
			return saveGenerated(chatRoom, resultName, "xlsx", edited, files);
		} catch (UnsupportedEditException e) {
			// 안전하게 처리 못 하는 파일은 이유를 밝히고 거절 — 깨진 파일을 주는 것보다 낫다
			return "(파일을 수정하지 못했습니다: " + e.getMessage() + ")";
		} catch (RuntimeException e) {
			log.error("AI 편집 파일 처리에 실패했습니다. chatRoomId={}, target={}",
				chatRoom.getId(), block.targetFileName(), e);
			return "(파일 수정에 실패했습니다: " + block.targetFileName() + ")";
		}
	}

	/** 편집 대상 원본 조회: 파일명 일치 → 없으면 채팅방의 최근 업로드 파일로 폴백 */
	private Optional<FileAsset> findOriginal(ChatRoom chatRoom, String targetFileName) {
		// 최신순 목록에서 첫 건만 사용 (LIMIT 파생 쿼리의 Oracle 호환 문제 회피 — 리포지토리 주석 참고)
		return fileAssetRepository
			.findByChatRoom_IdAndOriginalNameAndStatusOrderByIdDesc(
				chatRoom.getId(), targetFileName, FileAssetStatus.UPLOADED)
			.stream().findFirst()
			.or(() -> fileAssetRepository.findByChatRoom_IdAndStatusOrderByIdDesc(
				chatRoom.getId(), FileAssetStatus.UPLOADED)
				.stream().findFirst());
	}

	private String resolveResultName(EditBlock block, String originalName) {
		if (block.instruction().result() != null && !block.instruction().result().isBlank()) {
			return block.instruction().result();
		}
		int dot = originalName.lastIndexOf('.');
		String base = dot > 0 ? originalName.substring(0, dot) : originalName;
		return base + "_수정.xlsx";
	}

	/**
	 * 파일 바이트를 스토리지에 저장하고 FileAsset(GENERATED)을 기록한 뒤 안내 문구를 반환합니다.
	 *
	 * <p>안내 문구에 이모지(📎 등)를 쓰지 않는 이유: 이모지는 유니코드 보조평면 문자라
	 * 한글 전용 문자셋(MSWIN949 계열) Oracle에 저장되면 "??"로 파괴됩니다. 이 문구는
	 * 프런트(chat.js)가 다운로드 버튼을 복원하는 근거이므로, DB를 거쳐도 깨지지 않는
	 * 문자만 사용해야 합니다. 아이콘 표시는 프런트가 담당합니다.
	 */
	private String saveGenerated(ChatRoom chatRoom, String fileName, String extension, byte[] bytes,
		List<GeneratedFileResponse> files) {
		String storedPath = fileStorageService.store(bytes, extension);
		FileAsset asset = fileAssetRepository.save(FileAsset.createGenerated(
			chatRoom, fileName, storedPath, contentTypeOf(extension), bytes.length));

		files.add(GeneratedFileResponse.from(asset));
		return "생성된 파일: " + fileName + " (파일번호 " + asset.getId() + ")";
	}

	private byte[] toBytes(FileBlock block, String extension) {
		if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
			return block.body().getBytes(StandardCharsets.UTF_8);
		}
		if ("csv".equals(extension)) {
			// 엑셀이 UTF-8 CSV를 한글 깨짐 없이 열도록 BOM을 붙임
			byte[] body = block.body().getBytes(StandardCharsets.UTF_8);
			byte[] withBom = new byte[body.length + 3];
			withBom[0] = (byte) 0xEF;
			withBom[1] = (byte) 0xBB;
			withBom[2] = (byte) 0xBF;
			System.arraycopy(body, 0, withBom, 3, body.length);
			return withBom;
		}
		return excelFileWriter.write(aiFileBlockParser.parseCsv(block.body()));
	}

	/**
	 * 모델이 지정한 파일명을 안전하게 정제합니다.
	 * 경로 문자를 제거하고, 허용 목록 밖 확장자는 xlsx로 바꿉니다.
	 * (저장 경로는 어차피 UUID라 이 이름은 표시·다운로드용이지만, 다운로드 헤더에
	 * 실리는 값이므로 제어 문자와 경로 조작 문자를 걸러냅니다)
	 */
	private String sanitizeFileName(String rawName) {
		String name = rawName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
		if (name.isBlank()) {
			name = "생성파일";
		}
		if (name.length() > 100) {
			name = name.substring(0, 100);
		}

		String extension = extensionOf(name);
		if (!ALLOWED_EXTENSIONS.contains(extension)) {
			int dot = name.lastIndexOf('.');
			name = (dot > 0 ? name.substring(0, dot) : name) + ".xlsx";
		}
		return name;
	}

	private String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
	}

	private String contentTypeOf(String extension) {
		return switch (extension) {
			case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
			case "csv" -> "text/csv";
			case "md" -> "text/markdown";
			default -> "text/plain";
		};
	}
}
