package devple.gow.engine.anim;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.Skeleton;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 원작 루트 모션 (37 문서 §11.4) [확실: 코드].
 * <ul>
 *   <li><b>0x1F7110</b> (애니 컴포넌트 0x1F7C30 이 만든 객체, vtable 0x2BAF5C → 0x1F7F38): 매 프레임 프레임 카운터 +0xC8 를 올리고, 가장 무거운 애니 층
 *       (+0xCC.. 중 가중치 +0x18·+0x1C·+0x24 곱 최대) 의 zeroJoint 행렬 Z (0x116708) 와 역행렬 (0x185550) 을 구한다.
 *       카운터 ≥ 2 면 델타 D = Z · Zprev⁻¹ (행벡터, 열벡터로는 Zprev⁻¹ · Z) 를 +0x50..+0x80 에 저장, 카운터 &lt; 2 (새 애니 첫 프레임 — 0x1F7408 이
 *       새 애니에서 +0xC8 = 0) 면 <b>직전 D 를 그대로 둔다</b>. 어느 쪽이든 D 의 이동 행에 +0xEC (= 모델 행렬 첫 행 길이 = 오브젝트 배율, 0x1F7D64) 를 곱한다.
 *       Zprev⁻¹ = +0x10..+0x40. 그 다음 모델 관절 행렬 [zeroJoint] = 단위, [synchJoint] = S · Z⁻¹ 로 덮어쓴다 (0x204BF0 의 synch 기준).</li>
 *   <li><b>0x1EBD78</b> (영웅·적 공용 생물 갱신, 0x1EBF04): 전투 기술 중 (+0x324 → +0x4C ≠ 0) 에만 D 를 읽어 오브젝트 행렬 W (+0x50..) 를
 *       W' = D · W (행벡터) 로 — <b>회전은 W 에 바로</b>, 이동 (W'.t − W.t) 은 변위로 바꿔 내비게이션 이동 (0x1EB3D8 지상 / 0x1EAEC8 / 0x1C88F0) 에 넘긴다
 *       (벽·바닥 충돌을 거침). 동기 기술 (플래그 0x60) 에서 동기 상대가 끄는 중 (상대 +0x1AC 0x24, 0x2441E0) 이면 D = 단위 (끌리는 쪽은 루트 모션 없음).</li>
 * </ul>
 * 층 목록 +0xCC.. 은 새 애니를 틀 때 (0x1F6FC8 / 0x1F7408) 0x1006F8 → 0x107200 이 채운다: 채널의 블렌드 노드 중 <b>같은 act (새 애니)</b> 를
 * 가리키고 페이드아웃·종료 (+0x10 / +0x2C &amp; 0x90) 가 아닌 층만 [확실: 코드]. 크로스페이드로 사라지는 옛 애니는 들어가지 않으므로
 * "가장 무거운 층" 은 새 애니의 인스턴스 중 고르는 것이고 (블렌드 그룹 등), 옛 클립 → 새 클립의 zeroJoint 도약은 없다.
 * 엔진: 애니메이터의 현재 클립 (= 원작 층 목록, 인스턴스 하나) 을 샘플한다. 루프 클립이 한 바퀴 돈 프레임은 끝 → 처음을 이어 붙인다 [근사].
 */
public final class RootMotion {

	private int serial = Integer.MIN_VALUE;
	private Animation clip;
	private float lastTime;
	private int frames;
	private final Matrix4f prevInv = new Matrix4f();
	/** 마지막 로컬 델타 (열벡터 Zprev⁻¹ · Z, 배율 전). */
	private final Matrix4f delta = new Matrix4f();

	/** 마지막 {@link #step} 의 월드 변위 (yaw 회전·배율 적용) / 요 변화. */
	public float dx, dy, dz, dyaw;

	/** 다음 갱신을 새 애니 첫 프레임처럼 (상태 초기화, 0x1F7E48: D = 단위). */
	public void reset() {
		serial = Integer.MIN_VALUE;
		clip = null;
		frames = 0;
		delta.identity();
		dx = dy = dz = dyaw = 0;
	}

	/**
	 * 한 프레임 (애니 갱신 뒤): 0x1F7110 델타 → 월드 변위 R(yaw) · (배율 · D.t), 요 변화 = D 회전의 요.
	 * @param hold 동기 상대가 끄는 중 (D = 단위, 0x1EC058)
	 */
	public void step(Animator an, float scale, float yaw, boolean hold) {
		Matrix4f d = local(an);
		dx = dy = dz = dyaw = 0;
		if (hold) {
			return;
		}
		Vector3f t = d.getTranslation(new Vector3f()).mul(scale).rotateY(yaw);
		dx = t.x;
		dy = t.y;
		dz = t.z;
		Vector3f f = d.transformDirection(new Vector3f(0, 0, -1));
		if (f.x * f.x + f.z * f.z > 1e-8f) {
			dyaw = (float) Math.atan2(-f.x, -f.z);
		}
	}

	/** 0x1F7110: 로컬 델타 (열벡터 Zprev⁻¹ · Z). 새 재생의 첫 프레임은 직전 델타 유지. */
	public Matrix4f local(Animator an) {
		Animation a = an.current();
		Skeleton sk = an.skeleton();
		int zj = sk.indexOf("zeroJoint");
		if (a == null || zj < 0 || !a.hasSkeletalData()) {
			reset();
			return delta;
		}
		float time = an.time();
		if (a.looping() && a.duration > 0) {
			time = time % a.duration; // 애니메이터 시간은 루프에서도 계속 증가
			time = time < 0 ? time + a.duration : time;
		}
		if (an.playSerial() != serial || a != clip) {
			serial = an.playSerial();
			clip = a;
			frames = 0;
		}
		frames++;
		Matrix4f z = zero(a, sk, zj, time);
		if (frames >= 2) {
			if (a.looping() && time < lastTime && a.duration > 0) {
				// 루프가 돈 프레임: (끝 − 직전) + (지금 − 처음)
				Matrix4f end = zero(a, sk, zj, a.duration);
				Matrix4f start = zero(a, sk, zj, 0);
				delta.set(prevInv).mul(end).mul(start.invert()).mul(z);
			} else {
				delta.set(prevInv).mul(z);
			}
		}
		prevInv.set(z).invert();
		lastTime = time;
		return delta;
	}

	private static Matrix4f zero(Animation a, Skeleton sk, int zj, float t) {
		float tt = a.looping() ? t : Math.min(Math.max(t, 0), a.duration);
		return new Matrix4f().set(a.sample(sk, tt, a.looping()).world(sk)[zj]);
	}
}
