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
 * 0x00 u32 flags        bit0 반복(루프: standIdle, walk), bit1 1회(공격 등), 0x1000/0x2000 회전/이동 데이터, 0x200~0x800 블렌드 그룹
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
 * 0x64 StateDescr[dataTypeCount] 20B: { u16 ?, u16 trackCount, u32 ?, u32 offset(act 기준), f32 frameTime, u32 ? }
 * </pre>
 * 데이터 타입 0 = 관절 회전, 5 = 관절 이동 ({@link AnimTrack}).
 * 타입 10 = 오브젝트 클래스가 슬롯별 의미를 정하는 범용 채널. 디스크립터 +0x10 = 원소 크기 (4 = f32 이동 트랙과 같은 포맷,
 * 1 = 관절별 u8 상태). 히어로 슬롯 1 = 무기 로케이터 {@code lWeapOH}/{@code rWeapOH} 의 모델 공간 위치(블레이드 사슬 궤적),
 * 슬롯 2 = 관절별 부착 상태 바이트 (60 fps). 05 문서 2.8절.
 * 그 밖의 타입(3 머티리얼, 8 UV, 9 텍스처 시트, 11/12)은 디스크립터만 보관한다.
 */
public final class Animation {

	public record StateDescr(int typeId, int unk0, int trackCount, int unk4, int offset, float frameTime, int unk10) {
	}

	/**
	 * 타입 10 원소 크기 1 트랙: 12B 엔트리 { u16 id(관절 번호), u8 flags, u8 offHi, u16 count, u16 start, u16 hold, u16 offLo } +
	 * u8 값[count]. 프레임 단위 = 디스크립터 frameTime (히어로 1/60).
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

	public final String name;
	public final String group;
	public final int flags;
	public final float unk04;
	public final float speed;
	public final float duration;
	public final int blendIndex;
	public final float blendParam;
	public final int globalIndex;
	public final List<StateDescr> descrs = new ArrayList<>();
	public final List<AnimTrack> rotTracks = new ArrayList<>();
	public final List<AnimTrack> posTracks = new ArrayList<>();
	/** 타입 10 f32 채널 트랙 (슬롯 순서). 로케이터 관절 위치로 쓰일 수 있는 것은 bake 때 고른다 ({@link #locatorTrack}). */
	public final List<AnimTrack> channelTracks = new ArrayList<>();
	/**
	 * 타입 10 f32 채널을 상태 디스크립터별로 묶은 것 — <b>인덱스 = 디스크립터 번호</b> (모든 형의 디스크립터가 자리를 차지하고, 타입 10/unk10 4 가
	 * 아니거나 트랙이 없으면 빈 목록). 이펙트 GO 에서는 디스크립터 번호 = FXC 노드 헤더 +0x0A 링크 번호이고
	 * 스트림 k = 그 FXC 의 P 블록 k 번째 float (3 퍼짐, 4 속력, 8 초당 생성 수) 를 덮어쓴다 (40 문서 §3).
	 * 예 R_PERMK 상자 chestGrab: 디스크립터 3/6/9/12 = 링크 3/6/9/12 (MSemit1/HSemit1/PSemit1/WSemit1, 종류별 첫 이미터) [확실: 데이터 일치].
	 */
	public final List<List<AnimTrack>> channelGroups = new ArrayList<>();
	/**
	 * 타입 11 (unk10 = 4) 트랙. 이동 트랙(타입 5)이 없는 오브젝트 세트에서는 <b>관절 이동</b>으로 쓴다 [추정, 38 문서 §5]:
	 * ATHN05D 엘리베이터는 데이터 타입이 [0, 11, 11] 이고 스트림 5/9 (관절 1/2 의 Y) 절대값 138.47 = 바인드 로컬 Y, 이후 가산으로 -52.2 까지
	 * 내려간다 (발판이 190 내려가 Warp 도착점 WarpFrom4A 높이와 일치).
	 */
	public final List<AnimTrack> objectTracks = new ArrayList<>();
	/** 타입 10 u8 채널: 관절별 상태 값 (미노타우로스 maiMin01 에서 도끼 부착 관절 36→54 전환). */
	public final List<ByteTrack> byteTracks = new ArrayList<>();
	public final float frameTime;
	/** 위치 디스크립터의 frameTime (회전과 다르면 경고 대상, 히어로 전체에서는 항상 같음). */
	public final float posFrameTime;

	private final Map<Skeleton, Baked> baked = new IdentityHashMap<>();

	Animation(String group, ByteBuffer b, int at, int[] typeIds) {
		this.group = group;
		flags = b.getInt(at);
		unk04 = b.getFloat(at + 4);
		speed = b.getFloat(at + 0xC);
		blendIndex = b.getInt(at + 0x10);
		blendParam = b.getFloat(at + 0x14);
		globalIndex = b.getInt(at + 0x40);
		duration = b.getFloat(at + 0x1C);
		name = cstr(b, at + 0x24, 24);
		float ft = 0;
		float pft = 0;
		for (int k = 0; k < typeIds.length; k++) {
			int so = at + 0x64 + k * 0x14;
			StateDescr sd = new StateDescr(typeIds[k], Short.toUnsignedInt(b.getShort(so)), Short.toUnsignedInt(b.getShort(so + 2)),
					b.getInt(so + 4), b.getInt(so + 8), b.getFloat(so + 12), b.getInt(so + 16));
			descrs.add(sd);
			channelGroups.add(List.of()); // 디스크립터 번호 = 링크 번호 — 빈·다른 형 디스크립터도 자리를 차지한다
			if (sd.trackCount == 0) {
				continue;
			}
			if (typeIds[k] == 0) {
				rotTracks.addAll(AnimTrack.parse(b, at + sd.offset, sd.trackCount, false));
				ft = sd.frameTime;
			} else if (typeIds[k] == 5) {
				posTracks.addAll(AnimTrack.parse(b, at + sd.offset, sd.trackCount, true));
				pft = sd.frameTime;
			} else if (typeIds[k] == 11 && sd.unk10 == 4) {
				try {
					objectTracks.addAll(AnimTrack.parse(b, at + sd.offset, sd.trackCount, true));
				} catch (RuntimeException e) {
					// 해석 실패 무시
				}
			} else if (typeIds[k] == 10) {
				try {
					if (sd.unk10 == 4) {
						List<AnimTrack> g = AnimTrack.parse(b, at + sd.offset, sd.trackCount, true);
						channelTracks.addAll(g);
						channelGroups.set(k, g);
					} else if (sd.unk10 == 1) {
						byteTracks.addAll(parseBytes(b, at + sd.offset, sd.trackCount, sd.frameTime));
					}
				} catch (RuntimeException e) {
					// 오브젝트별 채널 — 해석 실패는 무시 (관절 포즈에는 영향 없음)
				}
			}
		}
		frameTime = ft > 0 ? ft : pft > 0 ? pft : 1f / 30f;
		posFrameTime = pft > 0 ? pft : frameTime;
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
			out.add(new ByteTrack(id, start, cnt, hold, v, ft));
		}
		return out;
	}

	/** 관절 id 의 u8 채널 값 (시간 t), 없으면 -1. */
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
	 * u8 채널을 <b>관절 가시성</b>으로 읽은 숨김 표 (41 문서 §2): 값 0 인 관절과 그 자손을 숨긴다 [추정: 의미·계층].
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

	/**
	 * 타입 10 f32 트랙이 이 스켈레톤의 <b>무기 로케이터</b>({@code *WeapOH}) 위치인지: 모든 스트림이 성분 x/y/z 이고, 관절이
	 * 루트의 자식, inverse bind 없음(메시가 붙지 않음), 쿼터니언, 같은 act 의 타입 5 이동이 건드리지 않는 관절.
	 * 슬롯 의미는 오브젝트 클래스가 정하므로 이름으로 히어로 바인딩만 적용한다 — 이름 조건 없이 고르면 통나무 조각/zeroJoint 등
	 * 의미 미상 채널도 걸린다 (anim:batch 통계, 05 문서 2.8).
	 */
	public boolean locatorTrack(Skeleton sk, AnimTrack t) {
		for (int s : t.streams()) {
			int j = s / 4;
			if (s % 4 > 2 || j >= sk.size()) {
				return false;
			}
			Skeleton.Joint jt = sk.joints.get(j);
			if (jt.parent() != 0 || jt.hasInverseBind() || !jt.quaternion() || !jt.name().endsWith("WeapOH")) {
				return false;
			}
			for (AnimTrack p : posTracks) {
				for (int ps : p.streams()) {
					if (ps / 4 == j) {
						return false;
					}
				}
			}
		}
		return true;
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
	 * 채널 묶음 group 의 스트림 stream 값 (시간 t 초, 프레임 사이 선형). 절대 트랙의 [start, start+count−1+hold] 구간만
	 * 정의되고, 어느 트랙도 덮지 않으면 def. 가산 트랙은 이펙트 채널에 없어 무시한다.
	 */
	public float channel(int group, int stream, float t, float def) {
		if (group < 0 || group >= channelGroups.size()) {
			return def;
		}
		float f = t / frameTime;
		for (AnimTrack tr : channelGroups.get(group)) {
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

	/** 프레임 수 (0 프레임 포함). duration/frameTime + 1 과 트랙 끝 프레임 중 큰 값. */
	public int frameCount() {
		int n = Math.round(duration / frameTime) + 1;
		for (AnimTrack t : rotTracks) {
			n = Math.max(n, t.endFrame() + 1);
		}
		for (AnimTrack t : posTracks) {
			n = Math.max(n, t.endFrame() + 1);
		}
		return n;
	}

	/** flags bit0 — 반복 재생 act 로 추정. */
	public boolean looping() {
		return (flags & 1) != 0;
	}

	public boolean hasSkeletalData() {
		return !rotTracks.isEmpty() || !posTracks.isEmpty() || !objectTracks.isEmpty();
	}

	/** 애니메이션이 건드리는 최대 관절 번호 + 1. */
	public int jointSpan() {
		int m = 0;
		for (AnimTrack t : rotTracks) {
			for (int s : t.streams()) {
				m = Math.max(m, s / 4 + 1);
			}
		}
		for (AnimTrack t : posTracks) {
			for (int s : t.streams()) {
				m = Math.max(m, s / 4 + 1);
			}
		}
		return m;
	}

	/** 프레임마다 모든 스트림 값을 펼친 테이블. rot/pos[frame][joint*4+c]. */
	public record Baked(int frames, float[][] rot, float[][] pos) {
	}

	/**
	 * 정수 프레임 단위로 스트림을 적분/대입해 펼친다.
	 * 초기값 = 스켈레톤 아이들 성분 (쿼터니언은 ×8 로 애니메이션 단위에 맞춤). 프레임마다 가산 트랙을 먼저 더하고
	 * 절대 트랙을 나중에 대입한다(경계 프레임에서는 절대값이 우선).
	 */
	public synchronized Baked bake(Skeleton sk) {
		return baked.computeIfAbsent(sk, this::doBake);
	}

	private Baked doBake(Skeleton sk) {
		int nj = sk.size();
		int frames = frameCount();
		float[] rot = new float[nj * 4];
		float[] pos = new float[nj * 4];
		for (int j = 0; j < nj; j++) {
			float qs = sk.joints.get(j).quaternion() ? Skeleton.IDLE_QUAT_TO_ANIM : 1f;
			for (int c = 0; c < 4; c++) {
				rot[j * 4 + c] = sk.idleRot[j][c] * qs;
			}
			for (int c = 0; c < 3; c++) {
				pos[j * 4 + c] = sk.idlePos[j][c];
			}
		}
		List<AnimTrack> pt = new ArrayList<>(posTracks);
		if (posTracks.isEmpty()) {
			pt.addAll(objectTracks);
		}
		for (AnimTrack t : channelTracks) {
			if (locatorTrack(sk, t)) {
				pt.add(t);
			}
		}
		float[][] outR = new float[frames][];
		float[][] outP = new float[frames][];
		for (int f = 0; f < frames; f++) {
			apply(rotTracks, rot, f, nj);
			apply(pt, pos, f, nj);
			outR[f] = rot.clone();
			outP[f] = pos.clone();
		}
		return new Baked(frames, outR, outP);
	}

	private static void apply(List<AnimTrack> tracks, float[] cur, int f, int nj) {
		for (AnimTrack t : tracks) {
			if (t.additive() && f > t.start() && f <= t.start() + t.count()) {
				int k = f - t.start() - 1;
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
	 * @param loop true 면 t 를 duration 으로 감싼다, false 면 [0, duration] 으로 자른다
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
				p.scale[j][c] = sk.idleScale[j][c];
			}
		}
		return p;
	}
}
