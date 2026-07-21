package haitai.safemask.domain.chatmessage.approval;

import static org.assertj.core.api.Assertions.assertThat;

import haitai.safemask.domain.fileasset.service.FileUploadPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FileFingerprintServiceTest {

	private final FileFingerprintService service = new FileFingerprintService(new FileUploadPolicy());

	@Test
	@DisplayName("파일명과 크기가 같아도 바이트가 바뀌면 SHA-256 지문이 달라진다")
	void fingerprintDetectsSameSizeByteChanges() {
		ApprovedFileFingerprint first = fingerprint("data.txt", "AAAA");
		ApprovedFileFingerprint changed = fingerprint("data.txt", "BBBB");

		assertThat(first.originalName()).isEqualTo(changed.originalName());
		assertThat(first.size()).isEqualTo(changed.size());
		assertThat(first.sha256()).isNotEqualTo(changed.sha256());
	}

	@Test
	@DisplayName("동일한 첨부는 요청을 다시 구성해도 같은 지문을 만든다")
	void identicalFileHasStableFingerprint() {
		assertThat(fingerprint("data.txt", "동일 내용"))
			.isEqualTo(fingerprint("data.txt", "동일 내용"));
	}

	private ApprovedFileFingerprint fingerprint(String name, String content) {
		MockMultipartFile file = new MockMultipartFile("files", name, "text/plain",
			content.getBytes(StandardCharsets.UTF_8));
		return service.fingerprint(List.of(file)).get(0);
	}
}
