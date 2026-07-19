package haitai.safemask.domain.airun.gateway;

import java.util.List;

@FunctionalInterface
public interface AiProgressListener {

	AiProgressListener NONE = (stage, message, sources) -> {
	};

	void onProgress(AiProgressStage stage, String message, List<WebSource> sources);
}
