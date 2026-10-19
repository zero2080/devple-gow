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
 * 데이터 타입 0 = 관절 회전, 5 = 관절 이동 ({@link AnimTrack}). 그 밖의 타입(3 머티리얼, 8 UV, 9 텍스처 시트,
 * 10/11/12 오브젝트 부속)은 아직 해석하지 않고 디스크립터만 보관한다.
 */
public final class Animation {

	public record StateDescr(int typeId, int unk0, int trackCount, int unk4, int offset, float frameTime, int unk10) {
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
			if (sd.trackCount == 0) {
				continue;
			}
			if (typeIds[k] == 0) {
				rotTracks.addAll(AnimTrack.parse(b, at + sd.offset, sd.trackCount, false));
				ft = sd.frameTime;
			} else if (typeIds[k] == 5) {
				posTracks.addAll(AnimTrack.parse(b, at + sd.offset, sd.trackCount, true));
				pft = sd.frameTime;
			}
		}
		frameTime = ft > 0 ? ft : pft > 0 ? pft : 1f / 30f;
		posFrameTime = pft > 0 ? pft : frameTime;
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
		return !rotTracks.isEmpty() || !posTracks.isEmpty();
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
		float[][] outR = new float[frames][];
		float[][] outP = new float[frames][];
		for (int f = 0; f < frames; f++) {
			apply(rotTracks, rot, f, nj);
			apply(posTracks, pos, f, nj);
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
