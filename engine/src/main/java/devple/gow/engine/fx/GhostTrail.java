package devple.gow.engine.fx;

import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SCR_GhostTrail — 머리 관절이 지나간 길을 따라 꼬리 관절을 늘어뜨린다 (사막 사이렌 영혼 gosirenspirit · gosirenspiritgrp, ATHN08C gobarwardog,
 * R_RDEAD godedred — 전 디스크 인스턴스 6 개, 모두 머리 = "tail" 관절 2). 원작 [확실: 코드]:
 * <ul>
 *   <li>SCR 처리 0x246918: 부품 +0x4C 가 없으면 0x20D988 로 0x24 바이트 객체를 만들고, 부품 +0x34 = 0x20E708 (켤 때 0x20DE20 = 기록을 지금 머리로
 *   채움), +0x2C = 0x25C558 (매 프레임 0x20DAA8 → 0x20DAD0 → 0x20E450). 소유 GO 가 숨었으면 (GO +0x4 bit 0) 갱신하지 않는다.</li>
 *   <li>0x20DB38: 머리 (부품 +0x46 = SCR 인스턴스 헤더 0x22, 템플릿 본문 문자열 "tail") 의 <b>직속 자식</b> 중 관절 플래그 0x20 인 것을 머리와의
 *   월드 거리로 스킵 리스트 (0x234088, 키 −거리, 내림차순) 에 넣어 가까운 순으로 늘어놓고, 앞 관절 (첫째는 머리) 과의 거리를 구간 길이로, 합을 꼬리
 *   길이로 둔다. 기록 수 = ⌊길이 × 0.125⌋ + 3 (사이렌 영혼: 관절 3–10, 0 · 16 × 7 = 112 → 17).</li>
 *   <li>0x20E450 (매 프레임): 머리 월드 위치가 기록 [0] 에서 8 보다 멀면 [0] 에서 머리 쪽으로 8 간 점 (위치 선형 · 회전 slerp) 을 앞에 끼우고 (끝은
 *   버림) 남은 거리 d 가 8 이하가 될 때까지 되풀이 — 기록은 호 길이 8 간격. 꼬리 관절 i 의 자리 f = (구간 길이 누적) − d:
 *   f &lt; 0 이면 머리와 [0] 사이 (위치 lerp, 회전 slerp, 0x20E10C), 아니면 s = max(1, ⌊f/8⌋), t = f/8 − s 로 [s−1 … s+2] 의 Catmull-Rom
 *   (위치 0x20DEF0, 회전은 slerp 피라미드 0x20DFC0 — Barry-Goldman, 매듭 −1 · 0 · 1 · 2). 그 회전·위치가 관절 월드가 된다 (로컬 = T · 머리⁻¹ 을
 *   +0x50 배열에 써 다음 자세 계산에서 월드 = T).</li>
 * </ul>
 * 사이렌 영혼은 이 꼬리가 비행 경로를 따라 휘어 보랏빛 긴 줄기가 된다 (영상 1491–1497, 2335, 2445–2447). 엔진은 꼬리가 바인드 자세 그대로 머리 뒤로 곧게
 * 112 뻗어 있었다 (43 §31.2-6).
 */
public final class GhostTrail {

	/** 기록 간격 (월드, 0x20E450 의 8.0 / 0x20E0D0 의 0.125). */
	public static final float SPACING = 8f;
	/** 관절 플래그: 꼬리로 끌려가는 관절 (0x20DB38 의 andi 0x20). */
	public static final int F_TRAIL = 0x20;

	private final int head;
	private final int[] chain;
	private final float[] seg;
	private final float length;
	private final Vector3f[] pos;
	private final Quaternionf[] rot;
	private boolean fresh;

	private GhostTrail(int head, int[] chain, float[] seg) {
		this.head = head;
		this.chain = chain;
		this.seg = seg;
		float l = 0;
		for (float s : seg) {
			l += s;
		}
		this.length = l;
		int n = (int) (l * 0.125f) + 3;
		pos = new Vector3f[n];
		rot = new Quaternionf[n];
		for (int i = 0; i < n; i++) {
			pos[i] = new Vector3f();
			rot[i] = new Quaternionf();
		}
	}

	/**
	 * 0x20DB38: 머리의 직속 자식 (첫 자식 → 다음 형제) 중 플래그 0x20 관절을 지금 월드 거리로 가까운 순 정렬 (같은 거리는 먼저 넣은 것이 뒤 —
	 * 스킵 리스트는 키 ≤ 인 첫 노드 앞에 끼운다).
	 *
	 * @param world 관절 월드 (행벡터 4×4, 생성 순간의 자세)
	 * @return 꼬리 관절이 없으면 null
	 */
	public static GhostTrail of(Skeleton sk, int head, float[][] world) {
		if (sk == null || head < 0 || head >= sk.size() || world == null || head >= world.length) {
			return null;
		}
		List<int[]> order = new ArrayList<>(); // {관절}
		List<Float> keys = new ArrayList<>();
		float[] h = world[head];
		for (int c = sk.joints.get(head).firstChild(); c >= 0 && c < sk.size(); c = sk.joints.get(c).nextSibling()) {
			if ((sk.joints.get(c).flags() & F_TRAIL) == 0 || c >= world.length) {
				continue;
			}
			float[] w = world[c];
			float key = -dist(w[12], w[13], w[14], h[12], h[13], h[14]);
			int at = 0;
			while (at < keys.size() && keys.get(at) > key) { // 내림차순 (0x233EA8: 다음 키 > 찾는 키 동안 전진)
				at++;
			}
			keys.add(at, key);
			order.add(at, new int[]{c});
		}
		if (order.isEmpty()) {
			return null;
		}
		int[] chain = new int[order.size()];
		float[] seg = new float[order.size()];
		float[] prev = h;
		for (int i = 0; i < chain.length; i++) {
			chain[i] = order.get(i)[0];
			float[] w = world[chain[i]];
			seg[i] = dist(w[12], w[13], w[14], prev[12], prev[13], prev[14]);
			prev = w;
		}
		GhostTrail gt = new GhostTrail(head, chain, seg);
		gt.fill(world[head]); // 0x20D988 → 0x20DE20: 생성할 때 기록을 그 머리로 채운다
		return gt;
	}

	/** 0x20DE20: 기록 전부 = 머리 위치·회전. */
	private void fill(float[] headWorld) {
		Matrix4f hm = new Matrix4f().set(headWorld);
		Quaternionf hq = hm.getNormalizedRotation(new Quaternionf());
		for (int i = 0; i < pos.length; i++) {
			pos[i].set(hm.m30(), hm.m31(), hm.m32());
			rot[i].set(hq);
		}
		fresh = false;
	}

	/** 머리 관절 (부품 +0x46). */
	public int head() {
		return head;
	}

	/** 꼬리 관절 (가까운 순). */
	public int[] chain() {
		return chain.clone();
	}

	/** 꼬리 길이 (구간 합, 0x20D988 이 객체 +0x08 에 둔다 — 그리기 반지름은 이것 × 1.5, 0x20DAD0). */
	public float length() {
		return length;
	}

	/** 기록 수 (⌊길이 / 8⌋ + 3). */
	public int samples() {
		return pos.length;
	}

	/** 다음 {@link #apply} 에서 기록을 그때 머리로 채운다 (0x20DE20 — 켤 때 부품 +0x34 = 0x20E708). */
	public void reset() {
		fresh = true;
	}

	/**
	 * 한 프레임 (0x20E450): 머리 기록을 밀고 꼬리 관절 월드를 길 위 자리로 바꾼 새 배열을 돌려준다 (입력은 그대로).
	 *
	 * @param world 관절 월드 (행벡터 4×4)
	 */
	public float[][] apply(float[][] world) {
		if (fresh) {
			fill(world[head]);
		}
		Matrix4f hm = new Matrix4f().set(world[head]); // 행벡터 행 우선 = JOML 열 우선 같은 메모리
		Vector3f hp = new Vector3f(hm.m30(), hm.m31(), hm.m32());
		Quaternionf hq = hm.getNormalizedRotation(new Quaternionf());
		float d = hp.distance(pos[0]);
		while (SPACING < d) {
			float t = SPACING / d;
			Vector3f np = new Vector3f(pos[0]).mul(1 - t).add(new Vector3f(hp).mul(t));
			Quaternionf nq = new Quaternionf(rot[0]).slerp(hq, t);
			Vector3f lastP = pos[pos.length - 1];
			Quaternionf lastQ = rot[rot.length - 1];
			System.arraycopy(pos, 0, pos, 1, pos.length - 1);
			System.arraycopy(rot, 0, rot, 1, rot.length - 1);
			pos[0] = lastP.set(np);
			rot[0] = lastQ.set(nq);
			d -= SPACING;
		}
		float[][] out = world.clone();
		float f = -d;
		Matrix4f m = new Matrix4f();
		for (int i = 0; i < chain.length; i++) {
			f += seg[i];
			at(f, d, hp, hq, m);
			float[] o = new float[16];
			m.get(o);
			out[chain[i]] = o;
		}
		return out;
	}

	/** 0x20E0D0: 길 위 거리 f (기록 [0] 기준, 음수 = 머리 쪽) 의 회전·위치 행렬. */
	private void at(float f, float d, Vector3f hp, Quaternionf hq, Matrix4f out) {
		Vector3f p = new Vector3f();
		Quaternionf q = new Quaternionf();
		if (f < 0) {
			float u = d > 1e-6f ? -f / d : 1; // [0] → 머리 쪽 비율
			p.set(pos[0]).mul(1 - u).add(new Vector3f(hp).mul(u));
			q.set(rot[0]).slerp(hq, u);
		} else {
			int s = Math.max(1, (int) (f * 0.125f));
			s = Math.min(s, pos.length - 3);
			float t = (f - s * SPACING) * 0.125f;
			catmullRom(pos[s - 1], pos[s], pos[s + 1], pos[s + 2], t, p);
			quatSpline(rot[s - 1], rot[s], rot[s + 1], rot[s + 2], t, q);
		}
		out.translationRotate(p.x, p.y, p.z, q.normalize());
	}

	/** 0x20DEF0: 0.5·(2P1 + (−P0 + P2)t + (2P0 − 5P1 + 4P2 − P3)t² + (−P0 + 3P1 − 3P2 + P3)t³). */
	static void catmullRom(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float t, Vector3f out) {
		float t2 = t * t, t3 = t2 * t;
		out.x = 0.5f * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
		out.y = 0.5f * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
		out.z = 0.5f * (2 * p1.z + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
	}

	/**
	 * 0x20DFC0: slerp 피라미드 (0x184388 = 최단 경로 slerp, 회전수 0) — L01 = slerp(q0, q1, t+1), L12 = slerp(q1, q2, t), L23 = slerp(q2, q3, t−1),
	 * L012 = slerp(L01, L12, (t+1)/2), L123 = slerp(L12, L23, t/2), 결과 = slerp(L012, L123, t).
	 */
	static void quatSpline(Quaternionf q0, Quaternionf q1, Quaternionf q2, Quaternionf q3, float t, Quaternionf out) {
		Quaternionf l01 = new Quaternionf(q0).slerp(q1, t + 1);
		Quaternionf l12 = new Quaternionf(q1).slerp(q2, t);
		Quaternionf l23 = new Quaternionf(q2).slerp(q3, t - 1);
		Quaternionf l012 = new Quaternionf(l01).slerp(l12, (t + 1) * 0.5f);
		Quaternionf l123 = new Quaternionf(l12).slerp(l23, t * 0.5f);
		out.set(l012).slerp(l123, t);
	}

	private static String cstr(ByteBuffer b, int off, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < max && off + i < b.limit(); i++) {
			byte c = b.get(off + i);
			if (c == 0) {
				break;
			}
			sb.append((char) (c & 0xFF));
		}
		return sb.toString();
	}

	private static float dist(float ax, float ay, float az, float bx, float by, float bz) {
		float x = ax - bx, y = ay - by, z = az - bz;
		return (float) Math.sqrt(x * x + y * y + z * z);
	}

	/**
	 * WAD 의 SCR_GhostTrail 인스턴스 (go 인스턴스 그룹 안, 헤더 0x20 bit 0x10) → 소유 GO (바로 앞 리소스, 소문자) → 머리 관절 (헤더 u16 0x22 —
	 * 템플릿 본문의 관절 이름 "tail" 과 같은 관절) [확실: 데이터 6 개 모두 2 = "tail"].
	 */
	public static Map<String, Integer> owners(WadFile wad) {
		Map<String, Integer> out = new HashMap<>();
		List<WadTag> tags = wad.tags();
		for (int k = 1; k < tags.size(); k++) {
			WadTag t = tags.get(k);
			if (!t.hasData() || t.size() < 0x24) {
				continue;
			}
			ByteBuffer d = t.data();
			if (d.getInt(0) != devple.gow.tools.fx.EnvScript.MAGIC || !"SCR_GhostTrail".equals(cstr(d, 4, 26))) {
				continue;
			}
			if ((Short.toUnsignedInt(d.getShort(0x20)) & 0x10) == 0) {
				continue; // 템플릿 (MDL 그룹)
			}
			out.put(tags.get(k - 1).name().toLowerCase(Locale.ROOT), (int) d.getShort(0x22));
		}
		return out;
	}
}
