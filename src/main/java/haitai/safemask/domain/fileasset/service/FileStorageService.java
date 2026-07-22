package haitai.safemask.domain.fileasset.service;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 파일 실체를 사내 스토리지(로컬 디스크)에 저장·조회합니다.
 *
 * <p>생성 파일에는 원복된 민감정보가 그대로 들어 있으므로 저장 경로는
 * 웹 정적 리소스 경로와 분리해야 하며(직접 URL 접근 불가),
 * 조회는 반드시 권한 검증을 거친 서비스 계층을 통해서만 이뤄져야 합니다.
 *
 * <p>DB에는 UUID 기반 저장 파일명만 기록합니다. 사용자가 지정한 파일명을
 * 경로에 쓰지 않으므로 경로 조작(../ 등)이나 파일명 충돌이 원천적으로 불가능합니다.
 */
@Slf4j
@Component
public class FileStorageService {
	public record StorageStatus(boolean writable, long usableBytes, long totalBytes) {
	}

	private final Path basePath;

	public FileStorageService(@Value("${safemask.storage.base-path:${java.io.tmpdir}/safemask/files}") String basePath) {
		this.basePath = Path.of(basePath).toAbsolutePath().normalize();
	}

	/**
	 * 바이트를 저장하고 DB에 기록할 저장 파일명(UUID.확장자)을 반환합니다.
	 *
	 * @param extension 점 없는 확장자 (예: "xlsx"). 저장 파일명 구성에만 사용됩니다.
	 */
	public String store(byte[] bytes, String extension) {
		String storedName = UUID.randomUUID() + "." + extension;
		try {
			Files.createDirectories(basePath);
			Files.write(basePath.resolve(storedName), bytes);
			return storedName;
		} catch (IOException e) {
			log.error("파일 저장에 실패했습니다. path={}", basePath.resolve(storedName), e);
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
		}
	}

	/** 저장 파일명으로 파일 내용을 읽습니다. 파일이 삭제·유실됐으면 NOT_FOUND로 처리합니다. */
	public byte[] load(String storedName) {
		// DB의 storedPath가 오염됐을 가능성에 대비해 최종 경로가 저장소 밖이면 거부
		Path target = basePath.resolve(storedName).normalize();
		if (!target.startsWith(basePath)) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}

		try {
			return Files.readAllBytes(target);
		} catch (IOException e) {
			log.warn("저장된 파일을 읽지 못했습니다. storedName={}", storedName, e);
			throw new CustomException(ErrorCode.NOT_FOUND, e);
		}
	}

	/**
	 * 저장된 파일을 삭제합니다. 파일이 이미 없으면 성공으로 봅니다.
	 * DB 상태 변경은 FileAssetService가 담당합니다.
	 */
	public void delete(String storedName) {
		Path target = basePath.resolve(storedName).normalize();
		if (!target.startsWith(basePath)) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}

		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.error("저장된 파일 삭제에 실패했습니다. storedName={}", storedName, e);
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
		}
	}

	/**
	 * 관리자 모니터링에서 저장소의 쓰기 가능 여부와 남은 용량만 확인합니다.
	 * 실제 경로는 운영 구조를 노출할 수 있으므로 응답에 포함하지 않습니다.
	 */
	public StorageStatus inspectStatus() throws IOException {
		Files.createDirectories(basePath);
		var fileStore = Files.getFileStore(basePath);
		return new StorageStatus(Files.isWritable(basePath), fileStore.getUsableSpace(), fileStore.getTotalSpace());
	}
}
