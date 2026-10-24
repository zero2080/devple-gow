package devple.gow.engine.script;


import java.util.HashSet;
import java.util.Set;

/**
 * 스크립트가 보는 게임 오브젝트 (원작 오브젝트 +0x2A 그룹 번호 = 엔티티 Filter 슬롯과 비교).
 * 플레이어는 Start 엔티티의 Filter(보통 1). SpawnEnemy 가 만든 적은 그 엔티티의 Filter.
 */
public final class ScriptActor {

	public final String name;
	public final int group;
	public final float[] pos = new float[3];
	/** 지난 틱 위치 (선분 통과 판정용, NaN = 없음 — {@code ScriptWorld.contacts}). */
	final float[] prev = {Float.NaN, Float.NaN, Float.NaN};
	/** 만든 SpawnEnemy (없으면 null). "부모 == 소유자" 판정(원작 +0x2C → +0x54)에 쓴다. */
	public final ScriptEntity spawner;
	/** 안에 있는 (볼륨, 영역) — 영역 -1 = 볼륨 전체 (영역이 맞지 않는 엔티티용). */
	final Set<ScriptWorld.VolumeRegion> inside = new HashSet<>();
	boolean destroyed;
	/**
	 * Creator 가 만든 오브젝트에 Animator 가 마지막으로 튼 act 와 반복 비트 — 넘겨 쓴 세계를 새 씬에 붙일 때 (recreate) 새 GO 가 이어서 튼다 (43 문서 §19).
	 */
	public String act;
	public boolean actLoop;

	public ScriptActor(String name, int group, float[] pos, ScriptEntity spawner) {
		this.name = name;
		this.group = group;
		this.spawner = spawner;
		if (pos != null) {
			System.arraycopy(pos, 0, this.pos, 0, 3);
		}
	}

	public void moveTo(float x, float y, float z) {
		pos[0] = x;
		pos[1] = y;
		pos[2] = z;
	}

	public boolean destroyed() {
		return destroyed;
	}

	@Override
	public String toString() {
		return name + "(g" + group + ")";
	}
}
