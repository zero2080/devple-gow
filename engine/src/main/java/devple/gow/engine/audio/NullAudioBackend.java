package devple.gow.engine.audio;

import devple.gow.tools.audio.AdpcmClip;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 장치 없는 백엔드. 클립 길이 / pitch 로 재생 시간을 흉내 내고, 시작한 보이스를 {@link #log()} 에 남긴다.
 * 스트림은 실제로 read 해서 디코딩 경로까지 검증한다 (tick 당 dt 만큼).
 */
public final class NullAudioBackend implements AudioBackend {

	/** 시작 기록: kind = "clip" / "stream". */
	public record Started(int id, String kind, String name, int rate, boolean loop, Voice voice) {
	}

	private static final class V {
		double remain;
		boolean loop;
		PcmStream stream;
		long streamed;
	}

	private final Map<Integer, V> voices = new HashMap<>();
	private final List<Started> log = new ArrayList<>();
	private final Vector3f listener = new Vector3f();
	private int next = 1;

	@Override
	public int play(AdpcmClip clip, Voice v) {
		V x = new V();
		x.loop = clip.looped();
		x.remain = clip.seconds() / Math.max(0.01f, v.pitch);
		int id = next++;
		voices.put(id, x);
		log.add(new Started(id, "clip", clip.name(), clip.rate(), x.loop, v.copy()));
		return id;
	}

	@Override
	public int stream(PcmStream s, Voice v) {
		V x = new V();
		x.stream = s;
		x.remain = Double.MAX_VALUE;
		int id = next++;
		voices.put(id, x);
		log.add(new Started(id, "stream", s.name(), s.sampleRate(), false, v.copy()));
		return id;
	}

	@Override
	public void update(int id, Voice v) {
	}

	@Override
	public boolean playing(int id) {
		return voices.containsKey(id);
	}

	@Override
	public void stop(int id) {
		V x = voices.remove(id);
		if (x != null && x.stream != null) {
			x.stream.close();
		}
	}

	@Override
	public void listener(Vector3fc pos, Vector3fc forward, Vector3fc up) {
		listener.set(pos);
	}

	@Override
	public void tick(float dt) {
		voices.entrySet().removeIf(e -> {
			V x = e.getValue();
			if (x.stream != null) {
				int want = Math.max(x.stream.channels(), (int) (dt * x.stream.sampleRate()) * x.stream.channels());
				short[] buf = new short[want];
				int n = x.stream.read(buf);
				x.streamed += n;
				if (n <= 0) {
					x.stream.close();
					return true;
				}
				return false;
			}
			if (x.loop) {
				return false;
			}
			x.remain -= dt;
			return x.remain <= 0;
		});
	}

	public List<Started> log() {
		return log;
	}

	/** 스트림 보이스가 지금까지 읽은 샘플 수 (없으면 -1). */
	public long streamed(int id) {
		V x = voices.get(id);
		return x != null && x.stream != null ? x.streamed : -1;
	}

	public Vector3f listenerPos() {
		return listener;
	}

	public int active() {
		return voices.size();
	}
}
