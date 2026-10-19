package devple.gow.engine.script;

import devple.gow.tools.level.LevelData;

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
	/** 만든 SpawnEnemy (없으면 null). "부모 == 소유자" 판정(원작 +0x2C → +0x54)에 쓴다. */
	public final ScriptEntity spawner;
	final Set<LevelData.PlacedVolume> inside = new HashSet<>();
	/** (볼륨, 영역 ID) 별 안/밖 (영역이 있는 진입/이탈 센서, 13 문서 §1). */
	final Set<Object> insideRegion = new HashSet<>();
	boolean destroyed;

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
