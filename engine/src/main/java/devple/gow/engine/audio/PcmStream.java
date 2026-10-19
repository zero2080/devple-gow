package devple.gow.engine.audio;

/** 블록 단위로 디코딩되는 PCM16 스트림 (채널 인터리브). */
public interface PcmStream extends AutoCloseable {

	int channels();

	int sampleRate();

	/** 최대 out.length 개 샘플(인터리브 단위, 채널 배수)을 채운다. 끝이면 0 (루프 스트림은 처음부터 계속). */
	int read(short[] out);

	/** 스트림 이름 (파일명). */
	String name();

	@Override
	default void close() {
	}
}
