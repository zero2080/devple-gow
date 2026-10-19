package devple.gow.tools.fx;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 머티리얼 애니메이션 (16 문서 §3). MAT 그룹 안의 ANM_ (서버 3) 이 mat+0x48 에 붙어 색 / UV / 회전 / 플립북 프레임을 움직인다 (MAT 생성 0x1537C8).
 * <pre>
 * ANM 세트(05 문서) → 그룹 0 → 액트 0. 타입 항목 u16 typeId, u8 p1, u8 p2:
 *   9 / 0       레이어 0 GFX 플립북 프레임 (u32 키)
 *   8 / 0x80+L  레이어 L UV 오프셋: 스트림 0 = U, 1 = V
 *   3 / 0       MAT 헤더 0x08 부터의 f32 (스트림 8 = 0x28 불투명도 p28)
 *   3 / L+1     레이어 L 의 0x28 부터: 0..3 rgba, 4 p38, 5 UV 회전(라디안) [추정]
 * 액트 +0x64: StateDescr[타입수] 20B (u16, u16 트랙수, u32, u32 오프셋, f32 키 간격 1/30, u32)
 * 트랙 12B: u16 기준 스트림, u8 플래그(bit1 = 비트맵 헤더), u8 오프셋 상위, u16 키 수, u16 시작 키, u16 유지, u16 오프셋 하위
 * </pre>
 * 값은 키(1/30 초)마다 절대값, 사이는 선형 보간. 액트 플래그 0x41 = 반복 자동 재생, 0x42 = 스크립트가 켜는 1회 재생.
 */
public final class MaterialAnim {

	/** 한 스트림의 키 열: 프레임 start 부터 values.length 개. */
	public record Track(int start, float[] values) {
	}

	/** 머티리얼 하나의 애니메이션 (없는 항목은 null). */
	public record Anim(String material, String anm, float duration, float keyTime, boolean loop, int[] frames,
					   Map<Integer, Track[]> uv, Map<String, Track> floats) {

		/** 시간 t 의 플립북 프레임 번호 (k = floor(t/키간격) mod 키수). */
		public int frame(float t) {
			if (frames == null || frames.length == 0) {
				return 0;
			}
			int k = (int) Math.floor(t / keyTime);
			return frames[loop ? Math.floorMod(k, frames.length) : Math.min(k, frames.length - 1)];
		}

		/** 레이어 L 의 UV 오프셋 (u, v), 없으면 null. */
		public float[] uv(int layer, float t) {
			Track[] tr = uv.get(layer);
			if (tr == null) {
				return null;
			}
			return new float[]{sample(tr[0], t, 0), sample(tr[1], t, 0)};
		}

		/** 이름("hdr8", "L0s5" …)의 값. */
		public float value(String key, float t, float def) {
			Track tr = floats.get(key);
			return tr == null ? def : sample(tr, t, def);
		}

		float sample(Track tr, float t, float def) {
			if (tr == null || tr.values.length == 0) {
				return def;
			}
			float total = duration > 0 ? duration : (tr.start + tr.values.length) * keyTime;
			float tt = loop && total > 0 ? (float) (t - Math.floor(t / total) * total) : t;
			float f = tt / keyTime - tr.start;
			if (f <= 0) {
				return tr.values[0];
			}
			int i = (int) f;
			if (i >= tr.values.length - 1) {
				return tr.values[tr.values.length - 1];
			}
			float a = f - i;
			return tr.values[i] * (1 - a) + tr.values[i + 1] * a;
		}
	}

	private MaterialAnim() {
	}

	/** WAD 의 MAT → 애니메이션. 키 = MAT 이름 (같은 이름이 여러 번 나오면 첫 것). */
	public static Map<String, Anim> load(WadFile wad) {
		Map<String, Anim> out = new LinkedHashMap<>();
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.magic() != 3 || t.size() < 0x40) {
				continue;
			}
			WadTag prev = null;
			for (int j = i - 1; j >= Math.max(0, i - 4); j--) {
				if (tags.get(j).tag() == WadTagType.GROUP_START) {
					continue;
				}
				prev = tags.get(j);
				break;
			}
			if (prev == null || prev.tag() != WadTagType.RESOURCE || !prev.hasData() || prev.magic() != 8 || out.containsKey(prev.name())) {
				continue;
			}
			try {
				Anim a = parse(prev.name(), t.name(), t.data());
				if (a != null) {
					out.put(prev.name(), a);
				}
			} catch (RuntimeException ex) {
				// 형식이 다른 ANM 은 건너뛴다
			}
		}
		return out;
	}

	static Anim parse(String mat, String anm, ByteBuffer d) {
		int ntype = Short.toUnsignedInt(d.getShort(0x10));
		int ngrp = Short.toUnsignedInt(d.getShort(0x12));
		if (ntype == 0 || ngrp == 0) {
			return null;
		}
		int go = d.getInt(0x18);
		int ao = go + d.getInt(go + 0x30);
		int aflags = d.getInt(ao);
		float dur = d.getFloat(ao + 0x1C);
		int[] frames = null;
		float keyTime = 1 / 30f;
		Map<Integer, Track[]> uv = new TreeMap<>();
		Map<String, Track> floats = new TreeMap<>();
		for (int k = 0; k < ntype; k++) {
			int e = 0x18 + 4 * ngrp + 4 * k;
			int tid = Short.toUnsignedInt(d.getShort(e));
			int p1 = Byte.toUnsignedInt(d.get(e + 2));
			int sd = ao + 0x64 + 20 * k;
			int tc = Short.toUnsignedInt(d.getShort(sd + 2));
			int doff = d.getInt(sd + 8);
			float ft = d.getFloat(sd + 12);
			if (ft > 0) {
				keyTime = ft;
			}
			int base = ao + doff;
			for (int tr = 0; tr < tc; tr++) {
				int te = base + 12 * tr;
				int bs = Short.toUnsignedInt(d.getShort(te));
				int fl = Byte.toUnsignedInt(d.get(te + 2));
				int ohi = Byte.toUnsignedInt(d.get(te + 3));
				int cnt = Short.toUnsignedInt(d.getShort(te + 4));
				int start = Short.toUnsignedInt(d.getShort(te + 6));
				int olo = Short.toUnsignedInt(d.getShort(te + 10));
				if (cnt == 0) {
					continue;
				}
				int dp = te + (ohi << 16 | olo);
				int[] streams;
				int ec;
				if ((fl & 2) != 0) {
					int wc = Byte.toUnsignedInt(d.get(dp));
					ec = Byte.toUnsignedInt(d.get(dp + 1));
					int dataOff = Short.toUnsignedInt(d.getShort(dp + 2));
					streams = new int[ec];
					int n = 0;
					for (int w = 0; w < wc; w++) {
						int bits = Short.toUnsignedInt(d.getShort(dp + 4 + 2 * w));
						for (int b = 0; b < 16 && n < ec; b++) {
							if ((bits >> b & 1) != 0) {
								streams[n++] = bs + w * 16 + b;
							}
						}
					}
					dp += dataOff;
				} else {
					ec = 1;
					streams = new int[]{bs};
				}
				for (int s = 0; s < ec; s++) {
					float[] v = new float[cnt];
					int[] iv = new int[cnt];
					for (int q = 0; q < cnt; q++) {
						int at = dp + (q * ec + s) * 4;
						v[q] = d.getFloat(at);
						iv[q] = d.getInt(at);
					}
					int st = streams[s];
					if (tid == 9) {
						frames = iv;
					} else if (tid == 8 && p1 >= 0x80) {
						Track[] pair = uv.computeIfAbsent(p1 - 0x80, x -> new Track[2]);
						if (st < 2) {
							pair[st] = new Track(start, v);
						}
					} else if (tid == 3) {
						floats.put((p1 == 0 ? "hdr" : "L" + (p1 - 1) + "s") + st, new Track(start, v));
					}
				}
			}
		}
		uv.values().forEach(p -> {
			for (int i = 0; i < 2; i++) {
				if (p[i] == null) {
					p[i] = new Track(0, new float[]{0});
				}
			}
		});
		return new Anim(mat, anm, dur, keyTime, (aflags & 0x01) != 0, frames, uv, floats);
	}
}
