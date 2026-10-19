package devple.gow.tools.level;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 볼륨 디버그 와이어 "mCDbgHdr" ({@code CDZ_ ENZ_ SEZ_ CMZ_}, COL 0x11). 짝이 되는 *V_ 볼륨의 외곽선.
 * <pre>
 * 0x00 u32 0x11  0x04 "mCDbgHdr"  0x0C u32 크기
 * 0x10 u32[4] 오프셋 (→hull별 정점 수 u16[], →hull별 인덱스 수 u16[], →정점 vec4, →선분 인덱스 u16)
 * hull 마다: 정점 vec4[n] (w=1), 선분 u16 쌍 (hull 안 로컬 번호). 0 인 정점 수 = 목록 끝(패딩)
 * </pre>
 */
public final class DebugWire {

	public final String name;
	public final float[][] vertices;
	public final int[] lines;

	public DebugWire(String name, ByteBuffer d) {
		this.name = name;
		int vcOff = d.getInt(0x10);
		int icOff = d.getInt(0x14);
		int vOff = d.getInt(0x18);
		int iOff = d.getInt(0x1C);
		List<float[]> vs = new ArrayList<>();
		List<Integer> ls = new ArrayList<>();
		int vi = 0;
		int ii = 0;
		for (int h = 0; vcOff + h * 2 < icOff; h++) {
			int vc = d.getShort(vcOff + h * 2) & 0xFFFF;
			int ic = d.getShort(icOff + h * 2) & 0xFFFF;
			if (vc == 0) {
				break;
			}
			int base = vs.size();
			for (int i = 0; i < vc; i++, vi++) {
				vs.add(new float[]{d.getFloat(vOff + vi * 16), d.getFloat(vOff + vi * 16 + 4), d.getFloat(vOff + vi * 16 + 8)});
			}
			for (int i = 0; i < ic; i++, ii++) {
				ls.add(base + (d.getShort(iOff + ii * 2) & 0xFFFF));
			}
		}
		vertices = vs.toArray(new float[0][]);
		lines = ls.stream().mapToInt(Integer::intValue).toArray();
	}

	public static boolean is(ByteBuffer d) {
		return d.limit() > 0x30 && d.getInt(0) == 0x11 && d.get(4) == 'm' && d.get(5) == 'C' && d.get(6) == 'D';
	}
}
