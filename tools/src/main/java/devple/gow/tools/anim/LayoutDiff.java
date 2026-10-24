package devple.gow.tools.anim;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code anim:layout <rom 디렉터리> [상위 N]} — ANM 디스크립터 배치 전환 (05 문서 §2.8.1) 의 효과 측정. 스켈레톤과 묶인 (CXT 그룹 참조) 모든 act 를
 * <b>예전 해석</b> (항목 번호 = 디스크립터 번호, 그 위의 보정 규칙: 타입 5 = 이동, 타입 5 가 없으면 타입 11 = 이동, 타입 10 의 {@code *WeapOH} 로케이터·
 * {@code "Anim"} 운반 관절, 타입 10 원소 1 = u8) 과 지금의 {@link Animation#sample} (Σ p2 배치: 회전·이동·스케일·로케이터, 타입 5 = u8) 로 각각 샘플해
 * 관절 월드 위치·회전 차이와 u8 가시성 차이를 센다. 예전 해석은 <b>비교 전용</b>으로 여기에만 남긴다 (엔진은 쓰지 않음).
 */
final class LayoutDiff {

	private LayoutDiff() {
	}

	/** 비교 결과 한 act. */
	record Row(String kind, String wad, String set, String act, String skel, String joint, float dPos, float dRot, boolean vis, String why) {
	}

	static void run(Path dir, int top) throws Exception {
		List<Path> wads;
		try (var s = Files.list(dir)) {
			wads = s.filter(p -> p.getFileName().toString().toUpperCase(Locale.ROOT).endsWith(".WAD")).sorted().toList();
		}
		List<Row> rows = new ArrayList<>();
		int acts = 0, scaleActs = 0, locatorActs = 0;
		Map<String, int[]> perKind = new TreeMap<>(); // kind → {acts, 자세 바뀜, 가시성 바뀜}
		Map<String, Integer> causes = new TreeMap<>();
		for (Path p : wads) {
			WadFile w = WadFile.load(p);
			List<Skeleton> sks;
			try {
				sks = Skeleton.loadAll(w);
			} catch (RuntimeException e) {
				continue;
			}
			for (WadTag t : w.resources()) {
				if (t.magic() != AnimationSet.MAGIC || !t.name().startsWith("ANM_")) {
					continue;
				}
				AnimationSet set;
				try {
					set = new AnimationSet(t.name(), t.data());
					set.attachAll(w);
				} catch (RuntimeException e) {
					continue;
				}
				List<ByteBuffer> bufs = new ArrayList<>();
				List<Integer> ats = new ArrayList<>();
				rawActs(w, t.data(), set, bufs, ats);
				List<Animation> all = set.all();
				if (all.size() != ats.size()) {
					continue;
				}
				for (Skeleton sk : sks) {
					if (!Skeleton.groupRefs(w, sk.name).contains(set.name)) {
						continue;
					}
					String kind = kind(p.getFileName().toString(), sk.name, set.name);
					int[] pk = perKind.computeIfAbsent(kind, k -> new int[3]);
					for (int i = 0; i < all.size(); i++) {
						Animation a = all.get(i);
						acts++;
						pk[0]++;
						scaleActs += a.scaleTracks.isEmpty() ? 0 : 1;
						locatorActs += a.locatorTracks.isEmpty() ? 0 : 1;
						Legacy old = new Legacy(bufs.get(i), ats.get(i), set.typeIds, a.duration);
						Row r = compare(kind, p.getFileName().toString(), set.name, a, old, sk);
						if (r.dPos > 0.5f || r.dRot > 0.02f) {
							pk[1]++;
							causes.merge(kind + " " + r.why, 1, Integer::sum);
						}
						if (r.vis) {
							pk[2]++;
						}
						if (r.dPos > 0.5f || r.dRot > 0.02f || r.vis) {
							rows.add(r);
						}
					}
				}
			}
		}
		System.out.printf("act %d (스켈레톤과 묶임), 스케일 트랙 %d, 로케이터 (타입 0·3) %d%n", acts, scaleActs, locatorActs);
		System.out.println("종류별 act / 자세 바뀜 (Δ위치 > 0.5 또는 Δ회전 > 0.02) / u8 가시성 바뀜:");
		perKind.forEach((k, v) -> System.out.printf("  %-8s %6d %6d %6d%n", k, v[0], v[1], v[2]));
		System.out.println("바뀐 원인 (종류 + 디스크립터):");
		causes.forEach((k, v) -> System.out.printf("  %5d × %s%n", v, k));
		System.out.println("Δ위치 상위 " + top + ":");
		rows.stream().sorted(Comparator.comparingDouble((Row r) -> -r.dPos)).limit(top).forEach(r ->
				System.out.printf("  %-7s %-10s %-24s %-22s skel=%-16s 관절=%-16s Δpos=%8.1f Δrot=%.3f %s%s%n", r.kind, r.wad, r.set, r.act, r.skel,
						r.joint, r.dPos, r.dRot, r.why, r.vis ? " +가시성" : ""));
		System.out.println("u8 가시성만 바뀐 act (상위 " + top + "):");
		rows.stream().filter(r -> r.vis && r.dPos <= 0.5f && r.dRot <= 0.02f).limit(top).forEach(r ->
				System.out.printf("  %-7s %-10s %-24s %-22s skel=%s%n", r.kind, r.wad, r.set, r.act, r.skel));
	}

	private static String kind(String wad, String skel, String set) {
		String s = skel.toLowerCase(Locale.ROOT);
		if (s.contains("cam") || set.toLowerCase(Locale.ROOT).startsWith("anm_cam")) {
			return "camera";
		}
		if (wad.startsWith("R_HERO")) {
			return "hero";
		}
		if (wad.startsWith("R_")) {
			return "R_*";
		}
		return "level";
	}

	/** set.all() 순서대로 act 의 (버퍼, 오프셋) — 외부 그룹은 같은 WAD 의 0x10003 리소스. */
	private static void rawActs(WadFile w, ByteBuffer b, AnimationSet set, List<ByteBuffer> bufs, List<Integer> ats) {
		int ng = Short.toUnsignedInt(b.getShort(0x12));
		for (int g = 0; g < ng; g++) {
			int go = b.getInt(0x18 + g * 4);
			AnimationSet.Group grp = set.groups.get(g);
			ByteBuffer gb = b;
			int gat = go;
			if ((b.getInt(go + 8) & AnimationSet.GROUP_EXTERNAL) != 0) {
				if (grp.loadedFrom == null) {
					continue;
				}
				gb = null;
				for (WadTag x : w.resources()) {
					if (x.magic() == AnimationSet.MAGIC_EXTERNAL && (w.name() + ":" + x.name()).equals(grp.loadedFrom)) {
						gb = x.data();
					}
				}
				if (gb == null) {
					continue;
				}
				gat = 8;
			}
			int n = gb.getInt(gat + 0xC);
			for (int j = 0; j < n; j++) {
				bufs.add(gb);
				ats.add(gat + gb.getInt(gat + 0x30 + j * 4));
			}
		}
	}

	private static Row compare(String kind, String wad, String set, Animation a, Legacy old, Skeleton sk) {
		float dPos = 0, dRot = 0;
		int worst = -1;
		boolean vis = false;
		for (int i = 0; i <= 8; i++) {
			float t = a.duration * i / 8f;
			float[][] wn = a.sample(sk, t, false).world(sk);
			float[][] wo = old.sample(sk, t).world(sk);
			for (int j = 0; j < sk.size(); j++) {
				float d = (float) Math.sqrt(sq(wn[j][12] - wo[j][12]) + sq(wn[j][13] - wo[j][13]) + sq(wn[j][14] - wo[j][14]));
				if (d > dPos) {
					dPos = d;
					worst = j;
				}
				for (int r = 0; r < 3; r++) {
					for (int c = 0; c < 3; c++) {
						dRot = Math.max(dRot, Math.abs(wn[j][r * 4 + c] - wo[j][r * 4 + c]));
					}
				}
			}
			vis |= !Arrays.equals(a.hiddenJoints(sk, t), old.hidden(sk, t));
		}
		StringBuilder why = new StringBuilder();
		if (!a.posTracks.isEmpty() && old.posSource != 1) {
			why.append("이동(0·1)").append(old.posSource == 11 ? "←예전 타입11" : old.posSource == 0 ? " 새로" : "").append(' ');
		}
		if (!a.scaleTracks.isEmpty()) {
			why.append("스케일(0·2) ");
		}
		if (!a.locatorTracks.isEmpty() && !old.locators) {
			why.append("로케이터(0·3) ");
		}
		if (old.posSource == 11 && a.posTracks.isEmpty()) {
			why.append("예전 타입11 이동 없앰 ");
		}
		if (old.transport) {
			why.append("예전 운반 관절 ");
		}
		if (Math.abs(old.frameTime - a.frameTime) > 1e-6f) {
			why.append("frameTime ");
		}
		return new Row(kind, wad.replace(".WAD", ""), set, a.name, sk.name, worst >= 0 ? sk.joints.get(worst).name() : "-", dPos, dRot, vis,
				why.toString().trim());
	}

	private static float sq(float x) {
		return x * x;
	}

	/**
	 * 예전 해석 (비교 전용): 디스크립터 k = 세트 항목 k (항목 수만큼만), 타입 0 = 회전, 타입 5 = 이동, 이동이 없고 세트가 타입 5 를 선언하지 않으면 타입 11
	 * (원소 4) = 이동, 타입 10 원소 4 트랙 중 루트 자식·inverse bind 없음·쿼터니언·이름 {@code *WeapOH} = 로케이터 위치, 이름 {@code "Anim"} (inverse bind 없음, 타입 5 없음)
	 * = 바인드 로컬에 더하는 운반 채널, 타입 10 원소 1 = u8. frameTime = 트랙 있는 타입 0 → 타입 5 → 1/30.
	 */
	static final class Legacy {
		final List<AnimTrack> rot = new ArrayList<>(), pos = new ArrayList<>(), obj = new ArrayList<>(), ch = new ArrayList<>();
		final List<Animation.ByteTrack> bytes = new ArrayList<>();
		final boolean declares5;
		final float frameTime;
		final float duration;
		/** 이동을 어디서 읽었나: 1 = 디스크립터 1 (타입 5 가 항목 1), 5 = 다른 자리의 타입 5, 11 = 타입 11, 0 = 없음. */
		int posSource;
		boolean locators, transport;

		Legacy(ByteBuffer b, int at, int[] typeIds, float duration) {
			this.duration = duration;
			float ft = 0, pft = 0;
			boolean d5 = false;
			for (int k = 0; k < typeIds.length; k++) {
				d5 |= typeIds[k] == 5;
				int so = at + 0x64 + k * 0x14;
				if (so + 0x14 > b.limit()) {
					break;
				}
				int tc = Short.toUnsignedInt(b.getShort(so + 2));
				int off = b.getInt(so + 8);
				float sft = b.getFloat(so + 12);
				int unk10 = b.getInt(so + 16);
				if (tc == 0) {
					continue;
				}
				try {
					if (typeIds[k] == 0) {
						rot.addAll(AnimTrack.parse(b, at + off, tc, false));
						ft = sft;
					} else if (typeIds[k] == 5) {
						pos.addAll(AnimTrack.parse(b, at + off, tc, true));
						pft = sft;
						posSource = k == 1 ? 1 : 5;
					} else if (typeIds[k] == 11 && unk10 == 4) {
						obj.addAll(AnimTrack.parse(b, at + off, tc, true));
					} else if (typeIds[k] == 10 && unk10 == 4) {
						ch.addAll(AnimTrack.parse(b, at + off, tc, true));
					} else if (typeIds[k] == 10 && unk10 == 1) {
						for (int i = 0; i < tc; i++) {
							int e = at + off + i * 12;
							int cnt = Short.toUnsignedInt(b.getShort(e + 4));
							int data = e + ((b.get(e + 3) & 0xFF) << 16) + Short.toUnsignedInt(b.getShort(e + 10));
							int[] v = new int[cnt];
							for (int f = 0; f < cnt; f++) {
								v[f] = b.get(data + f) & 0xFF;
							}
							bytes.add(new Animation.ByteTrack(Short.toUnsignedInt(b.getShort(e)), Short.toUnsignedInt(b.getShort(e + 6)), cnt,
									Short.toUnsignedInt(b.getShort(e + 8)), v, sft));
						}
					}
				} catch (RuntimeException e) {
					// 예전 파서도 무시했다
				}
			}
			declares5 = d5;
			frameTime = ft > 0 ? ft : pft > 0 ? pft : 1f / 30f;
			if (pos.isEmpty() && !declares5 && !obj.isEmpty()) {
				posSource = 11;
			}
		}

		Pose sample(Skeleton sk, float t) {
			int nj = sk.size();
			int frames = Math.round(duration / frameTime) + 1;
			for (AnimTrack x : rot) {
				frames = Math.max(frames, x.endFrame() + 1);
			}
			for (AnimTrack x : pos) {
				frames = Math.max(frames, x.endFrame() + 1);
			}
			List<AnimTrack> pt = new ArrayList<>(pos);
			if (pos.isEmpty() && !declares5) {
				pt.addAll(obj);
			}
			List<AnimTrack> tt = new ArrayList<>();
			for (AnimTrack x : ch) {
				if (locator(sk, x)) {
					pt.add(x);
					locators = true;
				} else if (transport(sk, x)) {
					tt.add(x);
					transport = true;
				}
			}
			float[] r = new float[nj * 4], q = new float[nj * 4], tp = new float[nj * 4];
			for (int j = 0; j < nj; j++) {
				float qs = sk.joints.get(j).quaternion() ? Skeleton.IDLE_QUAT_TO_ANIM : 1f;
				for (int c = 0; c < 4; c++) {
					r[j * 4 + c] = sk.idleRot[j][c] * qs;
				}
				for (int c = 0; c < 3; c++) {
					q[j * 4 + c] = sk.idlePos[j][c];
				}
			}
			float len = (frames - 1) * frameTime;
			t = len <= 0 ? 0 : Math.max(0, Math.min(len, t));
			float fp = t / frameTime;
			int f0 = Math.min((int) Math.floor(fp), frames - 1), f1 = Math.min(f0 + 1, frames - 1);
			float a = fp - f0;
			float[] r0 = null, q0 = null, t0 = null;
			for (int f = 0; f <= f1; f++) {
				step(rot, r, f, nj);
				step(pt, q, f, nj);
				step(tt, tp, f, nj);
				if (f == f0) {
					r0 = r.clone();
					q0 = q.clone();
					t0 = tp.clone();
				}
			}
			Pose p = new Pose(nj);
			for (int j = 0; j < nj; j++) {
				float dot = 0;
				if (sk.joints.get(j).quaternion()) {
					for (int c = 0; c < 4; c++) {
						dot += r0[j * 4 + c] * r[j * 4 + c];
					}
				}
				float sgn = dot < 0 ? -1 : 1;
				for (int c = 0; c < 4; c++) {
					p.rot[j][c] = r0[j * 4 + c] + (r[j * 4 + c] * sgn - r0[j * 4 + c]) * a;
				}
				for (int c = 0; c < 3; c++) {
					p.pos[j][c] = q0[j * 4 + c] + t0[j * 4 + c] + ((q[j * 4 + c] + tp[j * 4 + c]) - (q0[j * 4 + c] + t0[j * 4 + c])) * a;
					p.scale[j][c] = sk.idleScale[j][c];
				}
			}
			return p;
		}

		private static void step(List<AnimTrack> tracks, float[] cur, int f, int nj) {
			for (AnimTrack t : tracks) {
				if (t.additive() && f > t.start() && f <= t.start() + t.count()) {
					for (int e = 0; e < t.streams().length; e++) {
						if (t.streams()[e] < nj * 4) {
							cur[t.streams()[e]] += t.values()[e][f - t.start() - 1];
						}
					}
				}
			}
			for (AnimTrack t : tracks) {
				if (!t.additive() && f >= t.start() && f <= t.endFrame()) {
					for (int e = 0; e < t.streams().length; e++) {
						if (t.streams()[e] < nj * 4) {
							cur[t.streams()[e]] = t.values()[e][Math.min(f - t.start(), t.count() - 1)];
						}
					}
				}
			}
		}

		private boolean touchedByPos(int joint) {
			for (AnimTrack x : pos) {
				for (int s : x.streams()) {
					if (s / 4 == joint) {
						return true;
					}
				}
			}
			return false;
		}

		private boolean locator(Skeleton sk, AnimTrack t) {
			for (int s : t.streams()) {
				int j = s / 4;
				if (s % 4 > 2 || j >= sk.size()) {
					return false;
				}
				Skeleton.Joint jt = sk.joints.get(j);
				if (jt.parent() != 0 || jt.hasInverseBind() || !jt.quaternion() || !jt.name().endsWith("WeapOH") || touchedByPos(j)) {
					return false;
				}
			}
			return true;
		}

		private boolean transport(Skeleton sk, AnimTrack t) {
			if (declares5) {
				return false;
			}
			for (int s : t.streams()) {
				int j = s / 4;
				if (s % 4 > 2 || j >= sk.size() || !"Anim".equals(sk.joints.get(j).name()) || sk.joints.get(j).hasInverseBind() || touchedByPos(j)) {
					return false;
				}
			}
			return true;
		}

		boolean[] hidden(Skeleton sk, float t) {
			if (bytes.isEmpty()) {
				return null;
			}
			int n = sk.size();
			int[] v = new int[n];
			Arrays.fill(v, -1);
			for (Animation.ByteTrack bt : bytes) {
				if (bt.id() >= n || bt.count() == 0) {
					continue;
				}
				int f = Math.round(t / bt.frameTime());
				if (f >= bt.start()) {
					v[bt.id()] = bt.values()[Math.min(f - bt.start(), bt.count() - 1)];
				}
			}
			boolean[] h = new boolean[n];
			boolean any = false;
			for (int j = 0; j < n; j++) {
				for (int k = j, d = 0; k >= 0 && d < 64; k = sk.joints.get(k).parent(), d++) {
					if (v[k] == 0) {
						h[j] = true;
						break;
					}
				}
				any |= h[j];
			}
			return any ? h : null;
		}
	}
}
