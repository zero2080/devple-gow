package devple.gow.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * PS2 VIF1 코드 스트림 파서.
 * VU1 에 올라가는 데이터(UNPACK)를 풀어내고, MSCAL/MSCALF/MSCNT(마이크로프로그램 실행)마다
 * 하나의 {@link Batch} 로 끊는다. 실제 VU 메모리를 흉내 내지 않고 UNPACK 단위로 보관한다.
 * <p>
 * 처리 명령: NOP, STCYCL, OFFSET, BASE, ITOP, STMOD, MSKPATH3, MARK, FLUSH*, MSCAL*, STMASK, STROW, STCOL,
 * MPG(건너뜀), DIRECT/DIRECTHL(건너뜀), UNPACK 전 포맷(S/V2/V3/V4 × 32/16/8, V4-5).
 * STMASK 마스킹은 GoW 모델에서 쓰이지 않아 적용하지 않는다.
 */
public final class VifParser {

	/** MSCAL 한 번에 VU1 으로 넘어가는 데이터 묶음. */
	public record Batch(List<VifUnpack> unpacks, int mscalAddr, boolean terminated, int itop) {
	}

	private final ByteBuffer buf;
	private int cl = 1;
	private int wl = 1;
	private int mode;
	private final int[] row = new int[4];
	private int itop;
	private final List<String> warnings = new ArrayList<>();

	private VifParser(ByteBuffer buf) {
		this.buf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
	}

	/** buf[start, end) 구간의 VIF 코드를 해석한다. */
	public static List<Batch> parse(ByteBuffer buf, int start, int end) {
		return new VifParser(buf).run(start, end);
	}

	/** 경고 메시지까지 받고 싶을 때. */
	public static List<Batch> parse(ByteBuffer buf, int start, int end, List<String> warnOut) {
		VifParser p = new VifParser(buf);
		List<Batch> r = p.run(start, end);
		warnOut.addAll(p.warnings);
		return r;
	}

	private List<Batch> run(int start, int end) {
		List<Batch> out = new ArrayList<>();
		List<VifUnpack> cur = new ArrayList<>();
		int o = start;
		while (o + 4 <= end) {
			int code = buf.getInt(o);
			int cmd = (code >>> 24) & 0x7F;
			int num = (code >>> 16) & 0xFF;
			int imm = code & 0xFFFF;
			int at = o;
			o += 4;
			if (cmd >= 0x60) {
				o = unpack(cmd, num, imm, o, at, cur);
				continue;
			}
			switch (cmd) {
				case 0x00, 0x02, 0x03, 0x06, 0x07, 0x10, 0x11, 0x13 -> {
					// NOP, OFFSET, BASE, MSKPATH3, MARK, FLUSHE, FLUSH, FLUSHA: 정점 데이터와 무관
				}
				case 0x01 -> {
					cl = imm & 0xFF;
					wl = (imm >>> 8) & 0xFF;
				}
				case 0x04 -> itop = imm & 0x3FF;
				case 0x05 -> mode = imm & 3;
				case 0x14, 0x15, 0x17 -> {
					out.add(new Batch(List.copyOf(cur), cmd == 0x17 ? -1 : imm, true, itop));
					cur.clear();
				}
				case 0x20 -> o += 4;
				case 0x30 -> {
					for (int i = 0; i < 4; i++) {
						row[i] = buf.getInt(o + i * 4);
					}
					o += 16;
				}
				case 0x31 -> o += 16;
				case 0x4A -> o += (num == 0 ? 256 : num) * 8;
				case 0x50, 0x51 -> o += (imm == 0 ? 65536 : imm) * 16;
				default -> {
					warnings.add(String.format("알 수 없는 VIF 명령 %08x @%x", code, at));
					return finish(out, cur);
				}
			}
		}
		return finish(out, cur);
	}

	private List<Batch> finish(List<Batch> out, List<VifUnpack> cur) {
		if (!cur.isEmpty()) {
			out.add(new Batch(List.copyOf(cur), -1, false, itop));
		}
		return out;
	}

	private int unpack(int cmd, int num, int imm, int o, int at, List<VifUnpack> cur) {
		int vn = (cmd >>> 2) & 3;
		int vl = cmd & 3;
		int n = num == 0 ? 256 : num;
		boolean usn = (imm & 0x4000) != 0;
		int comps = vl == VifUnpack.VL_5 ? 4 : vn + 1;
		int bits = vl == VifUnpack.VL_5 ? 16 : (32 >> vl) * (vn + 1);
		int size = (bits * n + 31) / 32 * 4;
		int[] data = new int[n * comps];
		for (int i = 0; i < n; i++) {
			for (int c = 0; c < comps; c++) {
				int v;
				if (vl == VifUnpack.VL_5) {
					int p = Short.toUnsignedInt(buf.getShort(o + i * 2));
					v = c == 3 ? (p >>> 15) << 7 : ((p >>> (c * 5)) & 0x1F) << 3;
				} else {
					int idx = i * (vn + 1) + c;
					v = switch (vl) {
						case VifUnpack.VL_32 -> buf.getInt(o + idx * 4);
						case VifUnpack.VL_16 -> usn ? Short.toUnsignedInt(buf.getShort(o + idx * 2)) : buf.getShort(o + idx * 2);
						default -> usn ? Byte.toUnsignedInt(buf.get(o + idx)) : buf.get(o + idx);
					};
				}
				if (mode == 1) {
					v += row[c];
				} else if (mode == 2) {
					row[c] += v;
					v = row[c];
				}
				data[i * comps + c] = v;
			}
		}
		cur.add(new VifUnpack(vn, vl, imm & 0x3FF, usn, (imm & 0x8000) != 0, cl, wl, n, comps, data, at,
				mode == 1 ? row.clone() : null));
		return o + size;
	}
}
