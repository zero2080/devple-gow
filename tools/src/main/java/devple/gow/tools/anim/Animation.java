package devple.gow.tools.anim;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 애니메이션 하나 (ANM 그룹 안의 "act").
 * <pre>
 * 0x00 u32 flags        bit0 반복(루프: standIdle, walk), bit1 1회(공격 등), 0x1000/0x2000/0x4000 회전/이동/스케일 데이터, 0x200~0x800 블렌드 그룹
 * 0x04 f32 ?            (0, 0.09, 0.12, 2.0, 3.0 — 미상)
 * 0x08 u32 0x0B
 * 0x0C f32 1.0          (재생 속도로 추정)
 * 0x10 s32 blendIndex   블렌드 그룹(walkBlend0..2 등) 안의 슬롯, 일반 act 는 -1
 * 0x14 f32 blendParam   블렌드 축 값 (걷기 3.5 / 달리기 5.0 등)
 * 0x18 f32 ?            (0)
 * 0x1C f32 duration     초
 * 0x20 s32 -1
 * 0x24 char name[24]
 * 0x3C u32 size         act 데이터 크기
 * 0x40 u32 globalIndex  세트 전체에서의 act 번호 (그룹 firstAct + j)
 * 0x64 StateDescr[Σ p2] 20B: { u16 ?, u16 trackCount, u32 ?, u32 offset(act 기준), f32 frameTime, u32 원소 크기 }
 * </pre>
 * <b>디스크립터 배치</b> (05 문서 §2.8.1) [확실: 코드 + 전 WAD 데이터]: 세트 데이터 타입 항목 k 가 디스크립터 p2 개 (항목 +3 u8) 를 차례로 차지한다 —
 * 채널 생성 0x100EA8 이 항목마다 채널 객체를 만들고 +0x1C = 앞 항목 p2 합 (채널 id) 을 넣으며 (0x106328), FXC 이미터 링크는 이 id 로 채널을 찾는다 (0x130240).
 * 형별 디스크립터 (원소 크기 +0x10 이 형마다 하나):
 * <ul>
 * <li>타입 0 (관절 채널, vtable 0x2C0AB0) — 재생 0x111760 이 act+0x64 부터 차례로 읽는다: 0 회전 (s16 절대 / s8 가산) → 재생기 +0x5C,
 *     1 이동 (f32 / s16) → +0x60, 2 스케일 (s16 절대, (π/2)/4096 배 = 2607.6 이 1.0, 0x231B50) → +0x64, p2 ≥ 4 면 3 (f32) 도 이동 배열 +0x60 에 쓴다
 *     (0x1146C4: 채널 +0x20 (p2) &lt; 4 면 건너뜀 — 히어로 lWeapOH/rWeapOH 로케이터). 네 디스크립터 모두 디스크립터 0 의 frameTime (act+0x70) 으로 돈다.</li>
 * <li>타입 5 = 관절별 u8 상태 ({@link ByteTrack}, 41 문서 §1.3 가시성), 타입 10 = f32 채널 (FXC P 블록, 40 문서 §3), 타입 11 = 정수 값 채널 [추정],
 *     3/8/9 = 머티리얼 ({@code MaterialAnim}), 12 = LOD.</li>
 * </ul>
 * 예전 파서는 항목 번호 = 디스크립터 번호로 읽어 타입 0 뒤의 형 표시가 2 (히어로 3) 칸씩 밀려 있었다 — 그 위의 보정 규칙 ("타입 11 = 이동 / 타입 5 가 있으면
 * 아님", 운반 관절 "Anim", 히어로 "타입 10 슬롯 1/2" 로케이터) 은 모두 없앴다 (예전 해석과의 비교는 {@link LayoutDiff}).
 */
public final class Animation {

	public record StateDescr(int typeId, int unk0, int trackCount, int unk4, int offset, float frameTime, int unk10) {
	}

	/**
	 * 타입 5 (원소 크기 1) 트랙: 12B 엔트리 { u16 id(관절 번호), u8 flags, u8 offHi, u16 count, u16 start, u16 hold, u16 offLo } +
	 * u8 값[count]. 프레임 단위 = 그 디스크립터의 frameTime (히어로 1/60).
	 */
	public record ByteTrack(int id, int start, int count, int hold, int[] values, float frameTime) {

		/** 시간 t 의 값, 범위 밖이면 -1. */
		public int at(float t) {
			int f = Math.round(t / frameTime);
			if (f < start || f > start + count - 1 + hold || count == 0) {
				return -1;
			}
			return values[Math.min(f - start, count - 1)];
		}
	}

	/** 스케일 s16 → 배율: (π/2)/4096 (0x231B50 의 상수 0x39C90FD8, 아이들 쿼터니언 노름과 같은 단위 — 2607.6 = 1.0) [확실]. */
	public static final float SCALE_UNIT = Float.intBitsToFloat(0x39C90FD8);

	public final String name;
	public final String group;
	public final int flags;
	public final float unk04;
	public final float speed;
	public final float duration;
	public final int blendIndex;
	public final float blendParam;
	public final int globalIndex;
	/** 상태 디스크립터 (Σ p2 개, 인덱스 = 원작 채널 id). */
	public final List<StateDescr> records = new ArrayList<>();
	/** 타입 0 디스크립터 0: 관절 회전. */
	public final List<AnimTrack> rotTracks;
	/** 타입 0 디스크립터 1: 관절 이동 (로컬 위치 절대 키 + 가산). */
	public final List<AnimTrack> posTracks;
	/** 타입 0 디스크립터 2: 관절 스케일 (s16 절대, {@link #SCALE_UNIT} 배) — 값은 아직 원시 정수. */
	public final List<AnimTrack> scaleTracks;
	/** 타입 0 디스크립터 3 (p2 ≥ 4 세트만): 이동 배열에 이어서 쓴다 — 히어로 lWeapOH/rWeapOH 모델 공간 위치 (블레이드 사슬 궤적, 05 §2.8). */
	public final List<AnimTrack> locatorTracks;
	/** 타입 5: 관절별 u8 상태 (부서지는 물체의 관절 가시성 41 §1.3, 미노타우로스 도끼 손/바닥 전환). */
	public final List<ByteTrack> byteTracks = new ArrayList<>();
	/**
	 * 채널 id (= 디스크립터 번호) → 타입 10 f32 트랙 (다른 형·트랙 없음은 빈 목록). FXC 이미터 링크 (노드 +0x62) 가 이 id 로 찾고 (0x130240)
	 * 스트림 k = 그 FXC 의 P 블록 k 번째 float (3 퍼짐, 4 속력, 8 초당 생성 수) 를 덮어쓴다 (40 문서 §3).
	 * 예 ATHN03A GateExplosion 4 = FBFemit2GE 생성률 150, R_PERMK chestGrab 3/6/9/12 = MSemit1/HSemit1/PSemit1/WSemit1.
	 */
	public final List<List<AnimTrack>> channels = new ArrayList<>();
	/**
	 * 관절 자세의 프레임 간격: 타입 0 디스크립터 0 의 frameTime (재생 0x111760 이 네 디스크립터 모두에 쓴다, 0x111804). 타입 0 이 없으면 첫 디스크립터,
	 * 그래도 0 이면 1/30. 값 채널은 각자의 디스크립터 frameTime ({@link #channel}).
	 */
	public final float frameTime;

	private final Map<Skeleton, Baked> baked = new IdentityHashMap<>();

	/**
	 * @param recordCounts 항목마다 차지하는 디스크립터 수 (p2)
	 */
	Animation(String group, ByteBuffer b, int at, int[] typeIds, int[] recordCounts) {
		this.group = group;
		flags = b.getInt(at);
		unk04 = b.getFloat(at + 4);
		speed = b.getFloat(at + 0xC);
		blendIndex = b.getInt(at + 0x10);
		blendParam = b.getFloat(at + 0x14);
		globalIndex = b.getInt(at + 0x40);
		duration = b.getFloat(at + 0x1C);
		name = cstr(b, at + 0x24, 24);
		List<AnimTrack> rot = List.of(), pos = List.of(), scale = List.of(), loc = List.of();
		float ft = 0;
		int r = 0;
		for (int k = 0; k < typeIds.length; k++) {
			for (int sub = 0; sub < recordCounts[k]; sub++, r++) {
				int so = at + 0x64 + r * 0x14;
				if (so + 0x14 > b.limit()) {
					break;
				}
				StateDescr sd = new StateDescr(typeIds[k], Short.toUnsignedInt(b.getShort(so)), Short.toUnsignedInt(b.getShort(so + 2)),
						b.getInt(so + 4), b.getInt(so + 8), b.getFloat(so + 12), b.getInt(so + 16));
				records.add(sd);
				channels.add(List.of());
				int t = typeIds[k];
				if (t == 0 && sub == 0 || ft == 0 && r == 0) {
					ft = sd.frameTime; // act+0x70 (0x111804)
				}
				if (sd.trackCount == 0) {
					continue;
				}
				try {
					if (t == 0) {
						switch (sub) {
							case 0 -> rot = AnimTrack.parse(b, at + sd.offset, sd.trackCount, false);
							case 1 -> pos = AnimTrack.parse(b, at + sd.offset, sd.trackCount, true);
							case 2 -> scale = AnimTrack.parse(b, at + sd.offset, sd.trackCount, false);
							case 3 -> loc = AnimTrack.parse(b, at + sd.offset, sd.trackCount, true);
							default -> {
								// p2 > 4 인 타입 0 은 데이터에 없다
							}
						}
					} else if (t == 5 && sd.unk10 == 1) {
						byteTracks.addAll(parseBytes(b, at + sd.offset, sd.trackCount, sd.frameTime));
					} else if (t == 10 && sd.unk10 == 4) {
						channels.set(r, AnimTrack.parse(b, at + sd.offset, sd.trackCount, true));
					}
				} catch (RuntimeException e) {
					// 해석 실패 — 그 디스크립터만 빈 채로 (전 WAD 에서 실패 0, anim:batch)
				}
			}
		}
		rotTracks = rot;
		posTracks = pos;
		scaleTracks = scale;
		locatorTracks = loc;
		frameTime = ft > 0 ? ft : 1f / 30f;
	}

	private static List<ByteTrack> parseBytes(ByteBuffer b, int at, int entries, float ft) {
		List<ByteTrack> out = new ArrayList<>();
		for (int i = 0; i < entries; i++) {
			int e = at + i * 12;
			int id = Short.toUnsignedInt(b.getShort(e));
			int hi = b.get(e + 3) & 0xFF;
			int cnt = Short.toUnsignedInt(b.getShort(e + 4));
			int start = Short.toUnsignedInt(b.getShort(e + 6));
			int hold = Short.toUnsignedInt(b.getShort(e + 8));
			int data = e + (hi << 16) + Short.toUnsignedInt(b.getShort(e + 10));
			int[] v = new int[cnt];
			for (int f = 0; f < cnt; f++) {
				v[f] = b.get(data + f) & 0xFF;
			}
			out.add(new ByteTrack(id, start, cnt, hold, v, ft > 0 ? ft : 1f / 60f));
		}
		return out;
	}

	/** 관절 id 의 u8 상태 값 (시간 t), 없으면 -1. */
	public int byteChannel(int joint, float t) {
		int v = -1;
		for (ByteTrack bt : byteTracks) {
			if (bt.id() == joint) {
				int x = bt.at(t);
				if (x >= 0) {
					v = x;
				}
			}
		}
		return v;
	}

	/**
	 * u8 상태를 <b>관절 가시성</b>으로 읽은 숨김 표 (41 문서 §1.3): 값 0 인 관절과 그 자손을 숨긴다 [추정: 의미·계층].
	 * 부서지는 물체 act 에서 온전한 몸 관절 (doorVis, barrelVisDisapear, breakgroup1) 이 [1,1,0], 조각 묶음 (breakgroup2) 이 [0,0,1].
	 * 트랙이 끝난 뒤에도 마지막 값을 유지한다 (hold 0 인 통·양동이도 조각만 남아야 한다) [추정], 트랙 시작 전에는 보임.
	 *
	 * @return 관절별 숨김 (u8 트랙이 없으면 null)
	 */
	public boolean[] hiddenJoints(Skeleton sk, float t) {
		if (byteTracks.isEmpty()) {
			return null;
		}
		int n = sk.size();
		int[] v = new int[n];
		java.util.Arrays.fill(v, -1);
		for (ByteTrack bt : byteTracks) {
			if (bt.id() >= n || bt.count() == 0) {
				continue;
			}
			int f = Math.round(t / bt.frameTime());
			if (f < bt.start()) {
				continue;
			}
			v[bt.id()] = bt.values()[Math.min(f - bt.start(), bt.count() - 1)];
		}
		boolean[] h = new boolean[n];
		boolean any = false;
		for (int j = 0; j < n; j++) {
			h[j] = hiddenOf(sk, v, j, 0);
			any |= h[j];
		}
		return any ? h : null;
	}

	private static boolean hiddenOf(Skeleton sk, int[] v, int j, int depth) {
		if (j < 0 || depth > 64) {
			return false;
		}
		return v[j] == 0 || hiddenOf(sk, v, sk.joints.get(j).parent(), depth + 1);
	}

	static String cstr(ByteBuffer b, int at, int max) {
		int len = 0;
		while (len < max && b.get(at + len) != 0) {
			len++;
		}
		byte[] s = new byte[len];
		b.get(at, s);
		return new String(s, StandardCharsets.ISO_8859_1);
	}

	/**
	 * 채널 id 의 스트림 stream 값 (시간 t 초, 프레임 사이 선형). 절대 트랙의 [start, start+count−1+hold] 구간만
	 * 정의되고, 어느 트랙도 덮지 않으면 def. 가산 트랙은 타입 10 채널에 없어 무시한다.
	 * 프레임 간격 = <b>그 채널 디스크립터의 frameTime</b> — 값 채널 재생 0x268948 이 act + 0x64 + id·0x14 의 +0xC 를 읽는다 [확실]
	 * (부서지는 물체·석화 파괴 act 는 회전 1/15, 채널 1/30 — 전 WAD 407 디스크립터).
	 */
	public float channel(int id, int stream, float t, float def) {
		if (id < 0 || id >= channels.size()) {
			return def;
		}
		float ft = records.get(id).frameTime();
		float f = t / (ft > 0 ? ft : frameTime);
		for (AnimTrack tr : channels.get(id)) {
			if (tr.additive()) {
				continue;
			}
			int k = -1;
			for (int i = 0; i < tr.streams().length; i++) {
				if (tr.streams()[i] == stream) {
					k = i;
				}
			}
			if (k < 0 || f < tr.start() || f > tr.start() + tr.count() - 1 + tr.hold()) {
				continue;
			}
			float[] v = tr.values()[k];
			float x = Math.min(f - tr.start(), tr.count() - 1);
			int i0 = (int) Math.floor(x);
			int i1 = Math.min(i0 + 1, tr.count() - 1);
			return v[i0] + (v[i1] - v[i0]) * (x - i0);
		}
		return def;
	}

	/** 채널 id 에 타입 10 트랙이 있는가. */
	public boolean hasChannel(int id) {
		return id >= 0 && id < channels.size() && !channels.get(id).isEmpty();
	}

	/** 프레임 수 (0 프레임 포함). duration/frameTime + 1 과 트랙 끝 프레임 중 큰 값. */
	public int frameCount() {
		int n = Math.round(duration / frameTime) + 1;
		for (List<AnimTrack> l : List.of(rotTracks, posTracks, scaleTracks, locatorTracks)) {
			for (AnimTrack t : l) {
				n = Math.max(n, t.endFrame() + 1);
			}
		}
		return n;
	}

	/** flags bit0 — 반복 재생 act 로 추정. */
	public boolean looping() {
		return (flags & 1) != 0;
	}

	/** 관절 자세 트랙 (타입 0 의 회전·이동·스케일·로케이터) 이 하나라도 있는가. */
	public boolean hasSkeletalData() {
		return !rotTracks.isEmpty() || !posTracks.isEmpty() || !scaleTracks.isEmpty() || !locatorTracks.isEmpty();
	}

	/** 애니메이션이 건드리는 최대 관절 번호 + 1. */
	public int jointSpan() {
		int m = 0;
		for (List<AnimTrack> l : List.of(rotTracks, posTracks, scaleTracks, locatorTracks)) {
			for (AnimTrack t : l) {
				for (int s : t.streams()) {
					m = Math.max(m, s / 4 + 1);
				}
			}
		}
		return m;
	}

	/** 프레임마다 모든 스트림 값을 펼친 테이블. rot/pos/scale[frame][joint*4+c] (스케일 트랙이 없으면 scale = null → 아이들 스케일). */
	public record Baked(int frames, float[][] rot, float[][] pos, float[][] scale) {
	}

	/**
	 * 정수 프레임 단위로 스트림을 적분/대입해 펼친다.
	 * 초기값 = 스켈레톤 아이들 성분 (쿼터니언은 ×8 로 애니메이션 단위에 맞춤, 스케일은 CXT 아이들 스케일 — 재생기 리셋 0x111510 이 같은 일을 한다).
	 * 프레임마다 가산 트랙을 먼저 더하고 절대 트랙을 나중에 대입한다 (경계 프레임에서는 절대값이 우선). 이동은 디스크립터 1 다음 3.
	 */
	public synchronized Baked bake(Skeleton sk) {
		return baked.computeIfAbsent(sk, this::doBake);
	}

	private Baked doBake(Skeleton sk) {
		int nj = sk.size();
		int frames = frameCount();
		float[] rot = new float[nj * 4];
		float[] pos = new float[nj * 4];
		float[] scl = scaleTracks.isEmpty() ? null : new float[nj * 4];
		for (int j = 0; j < nj; j++) {
			float qs = sk.joints.get(j).quaternion() ? Skeleton.IDLE_QUAT_TO_ANIM : 1f;
			for (int c = 0; c < 4; c++) {
				rot[j * 4 + c] = sk.idleRot[j][c] * qs;
			}
			for (int c = 0; c < 3; c++) {
				pos[j * 4 + c] = sk.idlePos[j][c];
				if (scl != null) {
					scl[j * 4 + c] = sk.idleScale[j][c] / SCALE_UNIT; // 원시 정수 단위로 적분, 샘플 때 배율
				}
			}
		}
		List<AnimTrack> pt = posTracks;
		if (!locatorTracks.isEmpty()) {
			pt = new ArrayList<>(posTracks);
			pt.addAll(locatorTracks);
		}
		float[][] outR = new float[frames][];
		float[][] outP = new float[frames][];
		float[][] outS = scl == null ? null : new float[frames][];
		for (int f = 0; f < frames; f++) {
			apply(rotTracks, rot, f, nj);
			apply(pt, pos, f, nj);
			outR[f] = rot.clone();
			outP[f] = pos.clone();
			if (scl != null) {
				apply(scaleTracks, scl, f, nj);
				outS[f] = scl.clone();
			}
		}
		return new Baked(frames, outR, outP, outS);
	}

	private static void apply(List<AnimTrack> tracks, float[] cur, int f, int nj) {
		for (AnimTrack t : tracks) {
			// 가산 트랙의 hold: 마지막 변화량을 hold − 1 프레임 더 더한다 — 재생기 0x111760 은 트랙 창을 [start, start + count + hold − 1] 프레임으로
			// 거르고 (0x113F78–0x113FD0), 가산 이동 0x231E40 은 칸 번호를 count − 1 로 묶어 (0x231EBC–0x231ED0) 그 칸의 변화량을 지난 시간 비율만큼
			// 더한다 [확실: 디스어셈블]. 데이터: hold 끝 바로 뒤 절대 키와의 차 평균 531 → 3.5 (전 WAD 37 곳, 회전 17 · 이동 20 모두 줄어듦, 43 §52) —
			// PAND04F 승강기 goelevator "Elevator" 관절 1 y: 1 → 12 프레임 뒤 192 · 190 프레임 동안 1.918/프레임으로 올라 399 프레임 절대 664.9 에 닿는다.
			int end = t.start() + t.count() + Math.max(0, t.hold() - 1);
			if (t.additive() && f > t.start() && f <= end) {
				int k = Math.min(f - t.start() - 1, t.count() - 1);
				int[] s = t.streams();
				for (int e = 0; e < s.length; e++) {
					if (s[e] < nj * 4) {
						cur[s[e]] += t.values()[e][k];
					}
				}
			}
		}
		for (AnimTrack t : tracks) {
			if (!t.additive() && f >= t.start() && f <= t.endFrame()) {
				int k = Math.min(f - t.start(), t.count() - 1);
				int[] s = t.streams();
				for (int e = 0; e < s.length; e++) {
					if (s[e] < nj * 4) {
						cur[s[e]] = t.values()[e][k];
					}
				}
			}
		}
	}

	/**
	 * 시간 t(초) 의 포즈. 프레임 사이는 성분별 선형 보간 (쿼터니언은 이후 정규화 = nlerp).
	 *
	 * @param loop true 면 t 를 길이로 감싼다, false 면 [0, 길이] 로 자른다
	 */
	public Pose sample(Skeleton sk, float t, boolean loop) {
		Baked bk = bake(sk);
		float len = (bk.frames - 1) * frameTime;
		if (len <= 0) {
			t = 0;
		} else if (loop) {
			t = ((t % len) + len) % len;
		} else {
			t = Math.max(0, Math.min(len, t));
		}
		float fp = t / frameTime;
		int f0 = Math.min((int) Math.floor(fp), bk.frames - 1);
		int f1 = Math.min(f0 + 1, bk.frames - 1);
		float a = fp - f0;
		int nj = sk.size();
		Pose p = new Pose(nj);
		for (int j = 0; j < nj; j++) {
			boolean quat = sk.joints.get(j).quaternion();
			float dot = 0;
			if (quat) {
				for (int c = 0; c < 4; c++) {
					dot += bk.rot[f0][j * 4 + c] * bk.rot[f1][j * 4 + c];
				}
			}
			float sgn = dot < 0 ? -1 : 1;
			for (int c = 0; c < 4; c++) {
				float r0 = bk.rot[f0][j * 4 + c], r1 = bk.rot[f1][j * 4 + c] * sgn;
				p.rot[j][c] = r0 + (r1 - r0) * a;
			}
			for (int c = 0; c < 3; c++) {
				float p0 = bk.pos[f0][j * 4 + c], p1 = bk.pos[f1][j * 4 + c];
				p.pos[j][c] = p0 + (p1 - p0) * a;
				if (bk.scale == null) {
					p.scale[j][c] = sk.idleScale[j][c];
				} else {
					float s0 = bk.scale[f0][j * 4 + c], s1 = bk.scale[f1][j * 4 + c];
					p.scale[j][c] = (s0 + (s1 - s0) * a) * SCALE_UNIT;
				}
			}
		}
		return p;
	}
}
