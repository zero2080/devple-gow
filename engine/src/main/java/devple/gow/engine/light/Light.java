package devple.gow.engine.light;

import org.joml.Vector3f;

/**
 * 런타임 라이트 (원작 0xF0 바이트 오브젝트, 0x1514C0) — 위치·방향은 바뀔 수 있다 (블레이드 라이트 0x151928 / 0x151978).
 */
public final class Light {

	public final LightDef def;
	/** +0x10 위치 (월드 유닛). */
	public final Vector3f pos = new Vector3f();
	/** +0x20 방향 (정규화, 물체 → 라이트 쪽). */
	public final Vector3f dir = new Vector3f();
	/** +0xE0 동적 세기 배율 (만들 때 1, 블레이드 라이트는 0 에서 시작 — 0x1FAEE4). */
	public float dyn = 1f;
	/** 서버 목록에 들어 있는가 (vt+0x30 / vt+0x40). */
	public boolean active = true;
	/** 어느 WAD (슬롯 레벨) 의 라이트인가 — 다른 슬롯 레벨이 바뀌면 그 WAD 의 라이트를 뺀다 (47 문서 §8). null = 레벨 밖 (블레이드). */
	public String source;

	public Light(LightDef def) {
		this.def = def;
		pos.set(def.pos()[0], def.pos()[1], def.pos()[2]);
		dir.set(def.dir()[0], def.dir()[1], def.dir()[2]);
		// 0x1516xx: 방향이 0 이 아니면 0x151978 → 종류 비트 2 일 때 +0x20 = 정규화 방향 [확실]
		if ((def.type() & LightDef.DIRECTIONAL) != 0 && dir.lengthSquared() > 0) {
			dir.normalize();
		}
	}

	/** 0x151928: 위치를 옮긴다 (서버 공간 색인 다시 만듦 — 엔진은 매번 전체 검사라 할 일 없음). */
	public void setPosition(Vector3f p) {
		pos.set(p);
	}

	/**
	 * 블레이드 라이트 세기 램프 (0x201C78 켬 / 0x201DD8 끔, 45 문서 §3.5) [확실]: 켜면 +0xE0 += 4·dt (0.25 초에 1), 끄면 −= 2·dt (0.5 초에 0),
	 * [0, 1] 로 자름. 끄는 쪽이 0 이 되면 서버에서 뺀다 (vt+0x40), 켜는 쪽은 매 프레임 넣는다 (vt+0x30).
	 */
	public void ramp(boolean on, float dt) {
		if (on) {
			active = true;
			dyn = Math.max(0f, Math.min(1f, dyn + 4f * dt));
		} else {
			dyn = Math.max(0f, Math.min(1f, dyn - 2f * dt));
			if (dyn == 0f) {
				active = false;
			}
		}
	}

	@Override
	public String toString() {
		return def.name() + "(t" + def.type() + " g" + def.group() + ")";
	}
}
