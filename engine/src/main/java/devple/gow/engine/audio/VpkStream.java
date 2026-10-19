package devple.gow.engine.audio;

import devple.gow.tools.audio.PsAdpcm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * VPK 스트리밍 디코더 (09 문서 §3): 블록(전 채널 합계 8192B) 단위로 파일에서 읽어 디코딩한다.
 * 출력은 항상 스테레오(1ch 는 모노). 4채널은 (0,1) / (2,3) 두 스테레오 층을 {@link #layerGain} 으로 섞는다
 * [추정: _4 음악 = 2 개 스템, MusicGroupParam 이 층 볼륨을 바꾸는 것으로 보임].
 */
public final class VpkStream implements PcmStream {

	private final String name;
	private final FileChannel fc;
	private final int ch;
	private final int rate;
	private final int inter;
	private final int dataOff;
	private final int dataEnd;
	private final boolean loop;
	private final int outCh;
	/** 4채널 층 볼륨 (층 0 = 채널 0·1, 층 1 = 채널 2·3). */
	public final float[] layerGain = {1, 1};

	private PsAdpcm[] dec;
	private long pos;
	private short[] pend = new short[0];
	private int pendPos;
	private boolean ended;
	private final ByteBuffer blk;
	private final byte[] blkArr;

	public VpkStream(Path file, boolean loop) {
		this.name = file.getFileName().toString();
		this.loop = loop;
		try {
			fc = FileChannel.open(file, StandardOpenOption.READ);
			ByteBuffer h = ByteBuffer.allocate(0x18).order(ByteOrder.LITTLE_ENDIAN);
			fc.read(h, 0);
			int perCh = h.getInt(0x04);
			dataOff = h.getInt(0x08);
			int block = h.getInt(0x0C);
			rate = h.getInt(0x10);
			ch = Math.max(1, h.getInt(0x14));
			inter = block / ch;
			dataEnd = (int) Math.min(fc.size(), dataOff + (long) perCh * ch);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		outCh = ch == 1 ? 1 : 2;
		blkArr = new byte[inter * ch];
		blk = ByteBuffer.wrap(blkArr);
		rewind();
	}

	private void rewind() {
		pos = dataOff;
		dec = new PsAdpcm[ch];
		for (int c = 0; c < ch; c++) {
			dec[c] = new PsAdpcm();
		}
		ended = false;
	}

	@Override
	public int channels() {
		return outCh;
	}

	@Override
	public int sampleRate() {
		return rate;
	}

	@Override
	public String name() {
		return name;
	}

	/** 원본 채널 수 (2 / 4). */
	public int sourceChannels() {
		return ch;
	}

	@Override
	public int read(short[] out) {
		int n = 0;
		int guard = 0;
		while (n < out.length) {
			if (pendPos >= pend.length) {
				if (!decodeBlock()) {
					if (!loop || guard++ > 1) {
						break;
					}
					rewind();
					continue;
				}
			}
			int k = Math.min(out.length - n, pend.length - pendPos);
			System.arraycopy(pend, pendPos, out, n, k);
			pendPos += k;
			n += k;
		}
		return n - n % outCh;
	}

	private boolean decodeBlock() {
		if (ended || pos >= dataEnd) {
			return false;
		}
		blk.clear();
		try {
			int len = (int) Math.min(blkArr.length, dataEnd - pos);
			blk.limit(len);
			while (blk.hasRemaining() && fc.read(blk, pos + blk.position()) > 0) {
				// 채움
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int avail = blk.position();
		pos += blkArr.length;
		int frames = inter / 16;
		short[][] pcm = new short[ch][frames * 28];
		int len = Integer.MAX_VALUE;
		for (int c = 0; c < ch; c++) {
			int got = 0;
			for (int f = 0; f < frames; f++) {
				int o = c * inter + f * 16;
				if (o + 16 > avail || !dec[c].decodeFrame(blkArr, o, pcm[c], got)) {
					ended = true;
					break;
				}
				got += 28;
				if ((blkArr[o + 1] & 1) != 0 && !loop) {
					ended = true;
					break;
				}
			}
			len = Math.min(len, got);
		}
		if (len <= 0) {
			return false;
		}
		pend = new short[len * outCh];
		for (int i = 0; i < len; i++) {
			if (outCh == 1) {
				pend[i] = pcm[0][i];
			} else if (ch < 4) {
				pend[i * 2] = pcm[0][i];
				pend[i * 2 + 1] = pcm[1][i];
			} else {
				pend[i * 2] = clamp(pcm[0][i] * layerGain[0] + pcm[2][i] * layerGain[1]);
				pend[i * 2 + 1] = clamp(pcm[1][i] * layerGain[0] + pcm[3][i] * layerGain[1]);
			}
		}
		pendPos = 0;
		return true;
	}

	private static short clamp(float v) {
		return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
	}

	@Override
	public void close() {
		try {
			fc.close();
		} catch (IOException ignored) {
			// 닫기 실패 무시
		}
	}
}
