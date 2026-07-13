package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.global.exception.CustomException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class FileUploadPolicyTest {

	private final FileUploadPolicy policy = new FileUploadPolicy();

	@Test
	@DisplayName("파일 시그니처 검증은 전체 파일을 메모리에 올리지 않고 앞부분만 읽는다")
	void validatesSignatureWithoutReadingWholeFile() {
		MultipartFile file = new StreamOnlyMultipartFile(
			"document.pdf", "application/pdf", "%PDF-1.7\nlarge-body".getBytes());

		policy.validateOne(file);
	}

	@Test
	@DisplayName("시그니처가 확장자와 다르면 거절한다")
	void rejectsMismatchedSignature() {
		MultipartFile file = new StreamOnlyMultipartFile(
			"document.pdf", "application/pdf", "PK-not-a-pdf".getBytes());

		assertThatThrownBy(() -> policy.validateOne(file))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("실제 형식");
	}

	private record StreamOnlyMultipartFile(String originalFilename, String contentType, byte[] content)
		implements MultipartFile {

		@Override
		public String getName() {
			return "files";
		}

		@Override
		public String getOriginalFilename() {
			return originalFilename;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public boolean isEmpty() {
			return content.length == 0;
		}

		@Override
		public long getSize() {
			return content.length;
		}

		@Override
		public byte[] getBytes() {
			throw new AssertionError("FileUploadPolicy must not read the whole file for signature validation");
		}

		@Override
		public InputStream getInputStream() {
			return new ByteArrayInputStream(content);
		}

		@Override
		public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
			throw new UnsupportedOperationException();
		}
	}
}
