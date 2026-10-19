package devple.gow.tools.video;

import devple.gow.tools.audio.PsAdpcm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PSS 오디오 (private_stream_1 서브 0xFF) = Sony "SShd/SSbd" 헤더 + 채널 인터리브 블록 (18 문서 §3).
 * <pre>
 * "SShd" u32 0x18
 *   +0x08 u32 codec      1 = PCM16LE (전 동영상), 0x10 = PS-ADPCM (SDK 정의, 이 디스크엔 없음)
 *   +0x0C u32 rate       48000
 *   +0x10 u32 channels   2
 *   +0x14 u32 interleave 0x200 (채널당 블록 바이트)
 *   +0x18 s32 loopStart, +0x1C s32 loopEnd (-1)
 * "SSbd" u32 bodySize, 이후 [L 512B][R 512B][L 512B]... 가 패킷 경계와 무관하게 이어진다.
 * </pre>
 * 패킷 페이로드를 {@link #feed} 로 넣으면 채널 인터리브(LRLR) PCM16 을 꺼낼 수 있다.
 */
public final class SsAudio {

	public static final int CODEC_PCM16 = 1;
	public static final int CODEC_ADPCM = 0x10;

	public int codec = -1;
	public int rate;
	public int channels;
	public int interleave;
	public long bodySize;

	private final byte[] head = new byte[0x28];
	private int headLen;
	private byte[] block;
	private int blockLen;
	private PsAdpcm[] adpcm;
	private short[] out = new short[0];
	private int outLen;
	private long consumed;

	public boolean ready() {
		return codec >= 0;
	}

	/** 페이로드 바이트를 넣는다. 완성된 블록은 내부 PCM 버퍼에 쌓인다. */
	public void feed(byte[] src, int off, int len) {
		while (len > 0) {
			if (!ready()) {
				int n = Math.min(len, head.length - headLen);
				System.arraycopy(src, off, head, headLen, n);
				headLen += n;
				off += n;
				len -= n;
				if (headLen == head.length) {
					parseHeader();
				}
				continue;
			}
			if (bodySize > 0 && consumed >= bodySize) {
				return;
			}
			int n = Math.min(len, block.length - blockLen);
			System.arraycopy(src, off, block, blockLen, n);
			blockLen += n;
			consumed += n;
			off += n;
			len -= n;
			if (blockLen == block.length) {
				decodeBlock();
				blockLen = 0;
			}
		}
	}

	private void parseHeader() {
		ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x64685353 || b.getInt(0x20) != 0x64625353) {   // "SShd", "SSbd"
			throw new IllegalStateException("SShd/SSbd 헤더 아님");
		}
		codec = b.getInt(0x08);
		rate = b.getInt(0x0C);
		channels = b.getInt(0x10);
		interleave = b.getInt(0x14);
		bodySize = Integer.toUnsignedLong(b.getInt(0x24));
		block = new byte[interleave * channels];
		if (codec == CODEC_ADPCM) {
			adpcm = new PsAdpcm[channels];
			for (int c = 0; c < channels; c++) {
				adpcm[c] = new PsAdpcm();
			}
		} else if (codec != CODEC_PCM16) {
			throw new IllegalStateException("미지원 오디오 코덱 " + codec);
		}
	}

	private void decodeBlock() {
		int perCh = codec == CODEC_PCM16 ? interleave / 2 : interleave / 16 * 28;
		grow(perCh * channels);
		if (codec == CODEC_PCM16) {
			for (int c = 0; c < channels; c++) {
				int base = c * interleave;
				for (int i = 0; i < perCh; i++) {
					out[outLen + i * channels + c] = (short) ((block[base + 2 * i] & 0xFF) | (block[base + 2 * i + 1] << 8));
				}
			}
		} else {
			short[] tmp = new short[28];
			for (int c = 0; c < channels; c++) {
				for (int f = 0; f < interleave / 16; f++) {
					if (!adpcm[c].decodeFrame(block, c * interleave + f * 16, tmp, 0)) {
						java.util.Arrays.fill(tmp, (short) 0);
					}
					for (int i = 0; i < 28; i++) {
						out[outLen + (f * 28 + i) * channels + c] = tmp[i];
					}
				}
			}
		}
		outLen += perCh * channels;
	}

	private void grow(int n) {
		if (outLen + n > out.length) {
			out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, outLen + n));
		}
	}

	/** 쌓인 PCM 샘플 수 (채널 인터리브 단위 전체). */
	public int available() {
		return outLen;
	}

	/** 최대 dst.length 샘플을 꺼낸다. */
	public int drain(short[] dst, int off, int max) {
		int n = Math.min(max, outLen);
		System.arraycopy(out, 0, dst, off, n);
		System.arraycopy(out, n, out, 0, outLen - n);
		outLen -= n;
		return n;
	}

	@Override
	public String toString() {
		return ready() ? String.format("codec=%d %dHz %dch interleave=0x%x body=%d (%.2fs)", codec, rate, channels, interleave,
				bodySize, bodySize / (double) (rate * channels * (codec == CODEC_PCM16 ? 2.0 : 16.0 / 28))) : "(헤더 없음)";
	}
}
