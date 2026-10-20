package devple.gow.engine.audio;

import devple.gow.tools.audio.WavClip;

/** 메모리 PCM16 스트림 (모드 WAV 교체 중 스테레오, 49 문서 §11). 루프가 있으면 끝에서 루프 시작으로 돌아간다. */
final class MemoryPcmStream implements PcmStream {

	private final WavClip clip;
	private final short[] s;
	private final int ch;
	private int pos;

	MemoryPcmStream(WavClip clip) {
		this.clip = clip;
		this.s = clip.pcm().samples();
		this.ch = clip.channels();
	}

	@Override
	public int channels() {
		return ch;
	}

	@Override
	public int sampleRate() {
		return clip.pcm().sampleRate();
	}

	@Override
	public int read(short[] out) {
		int want = out.length - out.length % ch, n = 0;
		while (n < want) {
			if (pos >= s.length) {
				if (clip.loopStart() < 0 || clip.loopStart() * ch >= s.length) {
					break;
				}
				pos = clip.loopStart() * ch;
			}
			int k = Math.min(want - n, s.length - pos);
			System.arraycopy(s, pos, out, n, k);
			pos += k;
			n += k;
		}
		return n;
	}

	@Override
	public String name() {
		return clip.pcm().name();
	}
}
