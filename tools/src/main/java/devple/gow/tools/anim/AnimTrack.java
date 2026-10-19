package devple.gow.tools.anim;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 키프레임 트랙 = 여러 "스트림"(관절×4 + 성분) 을 같은 프레임 범위로 묶은 블록.
 * <pre>
 * 트랙 엔트리 12B (상태 디스크립터 offset 이 가리키는 배열):
 *   u16 baseStream   첫 스트림 번호 (스트림 = joint*4 + 성분)
 *   u8  flags        bit0 = 가산(델타), bit1 = 비트맵 명시, bit4-7(부호) = 원소 1개일 때 시프트
 *   u8  offHi        데이터 오프셋 상위 바이트 (offset = offHi&lt;&lt;16 | offLo, 엔트리 기준)
 *   u16 count        샘플 수. 0 이면 "서브 트랙 묶음" (아래)
 *   u16 start        시작 프레임
 *   u16 hold         마지막 샘플 이후 유지 프레임 수
 *   u16 offLo
 * count == 0 일 때 데이터:  u8 addCount, u8 total, Mgr[total]{u16 count,start,hold,offLo}, 이어서 공용 비트맵.
 *   앞 addCount 개는 가산 서브 트랙, 나머지는 절대값 서브 트랙. 서브 트랙 데이터 = 엔트리 + (offHi&lt;&lt;16 | Mgr.offLo).
 * 비트맵: u8 wordCount, u8 elemCount, u16 dataOffset, u16 words[wordCount], s8 shifts[elemCount] (elemCount&gt;1)
 *   word w 의 비트 b → 스트림 baseStream + w*16 + b.  flags bit1 이 없으면 기본 {1,1,0,0x0001} (스트림 1개, 데이터 바로 시작).
 * 샘플 위치: 데이터 + dataOffset + frame*elemCount*size + elem*size
 *   회전 절대 s16 / 회전 가산 s8·2^-shift / 위치 절대 f32 / 위치 가산 s16·2^-shift/256
 * </pre>
 * 가산 트랙의 샘플 k 는 프레임 start+k → start+k+1 구간의 변화량이다.
 */
public record AnimTrack(int baseStream, int flags, boolean additive, int start, int count, int hold, int[] streams,
						float[][] values) {

	private static final byte[] DEFAULT_BITMAP = {1, 1, 0, 0, 1, 0, 0, 0};

	/** 마지막으로 값이 정의되는 프레임. 절대: start+count-1+hold, 가산: start+count. */
	public int endFrame() {
		return additive ? start + count : start + count - 1 + hold;
	}

	/**
	 * 트랙 엔트리 배열 파싱.
	 *
	 * @param position false = 회전 스트림, true = 위치 스트림
	 */
	public static List<AnimTrack> parse(ByteBuffer b, int at, int entries, boolean position) {
		List<AnimTrack> out = new ArrayList<>();
		for (int i = 0; i < entries; i++) {
			int e = at + i * 12;
			int base = Short.toUnsignedInt(b.getShort(e));
			int flags = b.get(e + 2) & 0xFF;
			int hi = b.get(e + 3) & 0xFF;
			int cnt = Short.toUnsignedInt(b.getShort(e + 4));
			int start = Short.toUnsignedInt(b.getShort(e + 6));
			int hold = Short.toUnsignedInt(b.getShort(e + 8));
			int lo = Short.toUnsignedInt(b.getShort(e + 10));
			int data = e + (hi << 16) + lo;
			if (cnt != 0) {
				out.add(read(b, base, flags, (flags & 1) != 0, start, cnt, hold, data, data, position));
			} else {
				int na = b.get(data) & 0xFF;
				int nt = b.get(data + 1) & 0xFF;
				int bm = data + 2 + nt * 8;
				for (int k = 0; k < nt; k++) {
					int m = data + 2 + k * 8;
					int c2 = Short.toUnsignedInt(b.getShort(m));
					int s2 = Short.toUnsignedInt(b.getShort(m + 2));
					int h2 = Short.toUnsignedInt(b.getShort(m + 4));
					int o2 = Short.toUnsignedInt(b.getShort(m + 6));
					out.add(read(b, base, flags, k < na, s2, c2, h2, e + (hi << 16) + o2, bm, position));
				}
			}
		}
		return out;
	}

	private static AnimTrack read(ByteBuffer b, int base, int flags, boolean add, int start, int count, int hold,
								  int data, int bitmapAt, boolean position) {
		ByteBuffer bm;
		int bo;
		if ((flags & 2) != 0) {
			bm = b;
			bo = bitmapAt;
		} else {
			bm = ByteBuffer.wrap(DEFAULT_BITMAP).order(b.order());
			bo = 0;
		}
		int words = bm.get(bo) & 0xFF;
		int elems = bm.get(bo + 1) & 0xFF;
		int dataOff = Short.toUnsignedInt(bm.getShort(bo + 2));
		int[] streams = new int[elems];
		int n = 0;
		for (int w = 0; w < words; w++) {
			int mask = Short.toUnsignedInt(bm.getShort(bo + 4 + w * 2));
			for (int bit = 0; bit < 16; bit++) {
				if ((mask >> bit & 1) != 0 && n < elems) {
					streams[n++] = base + w * 16 + bit;
				}
			}
		}
		if (n != elems) {
			throw new IllegalStateException("비트맵 비트 수 " + n + " != " + elems);
		}
		int[] shifts = new int[elems];
		if (elems == 1) {
			shifts[0] = ((byte) flags) >> 4;
		} else {
			for (int k = 0; k < elems; k++) {
				shifts[k] = bm.get(bo + 4 + words * 2 + k);
			}
		}
		int size = position ? (add ? 2 : 4) : (add ? 1 : 2);
		float[][] values = new float[elems][count];
		for (int k = 0; k < elems; k++) {
			float coeff = shifts[k] == 0 ? 1f : shifts[k] < 0 ? (float) (1 << -shifts[k]) : 1f / (1 << shifts[k]);
			for (int f = 0; f < count; f++) {
				int o = data + dataOff + f * elems * size + k * size;
				float v;
				if (position) {
					v = add ? b.getShort(o) * coeff / 256f : b.getFloat(o);
				} else {
					v = add ? b.get(o) * coeff : b.getShort(o);
				}
				values[k][f] = v;
			}
		}
		return new AnimTrack(base, flags, add, start, count, hold, streams, values);
	}
}
