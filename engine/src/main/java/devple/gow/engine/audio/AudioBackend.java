package devple.gow.engine.audio;

import devple.gow.tools.audio.AdpcmClip;
import org.joml.Vector3fc;

/**
 * 보이스 출력 장치. {@link OpenAlBackend} (실제 재생) / {@link NullAudioBackend} (헤드리스·테스트).
 * 보이스 id 는 0 보다 크다 (0 = 실패).
 */
public interface AudioBackend extends AutoCloseable {

	/** 메모리 클립 재생 (클립의 loopStart 가 있으면 그 지점부터 반복). */
	int play(AdpcmClip clip, Voice v);

	/** 스트림 재생 (VPK). */
	int stream(PcmStream s, Voice v);

	/** gain / pitch / 위치 변경 반영. */
	void update(int id, Voice v);

	boolean playing(int id);

	void stop(int id);

	void listener(Vector3fc pos, Vector3fc forward, Vector3fc up);

	/** 매 프레임: 스트림 버퍼 보충, 끝난 보이스 정리. */
	void tick(float dt);

	@Override
	default void close() {
	}
}
