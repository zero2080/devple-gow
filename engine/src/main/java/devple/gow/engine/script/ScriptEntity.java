package devple.gow.engine.script;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.script.EntitySlots;
import devple.gow.tools.script.EscScript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행 중인 SCR_Entities 엔티티 하나 (원작 0xB0..0x140 바이트 객체, 13 문서 §1).
 * <pre>
 * world  = 엔티티 행렬 · 소유 GO 관절(+0x40) 월드   (원작 0x1A9CD0)
 * volume = 소유 GO 의 ENV_ 볼륨 (진입/이탈 센서, "볼륨 안" 모드)
 * </pre>
 */
public final class ScriptEntity {

	public final String script;
	public final EscScript.Entity data;
	/** 소유 GO 배치 (없으면 null). */
	public final LevelLayout.Placement owner;
	public final float[] world;
	public final LevelData.PlacedVolume volume;
	final Map<Integer, EscScript.Handler> handlers = new HashMap<>();
	/** 마지막 발동 시각 (재발동 대기, 원작 +0x68 초기값 -FLT_MAX). */
	float lastTrigger = -Float.MAX_VALUE;
	/** 받은 이벤트 인자 3개 (Entity:0x9..0xC, 원작 +0x5C..). */
	final Object[] eventArgs = {0, 0, 0};
	int spawnedDeaths;
	/** SpawnEnemy 가 만든 수 (원작 +0x100, MaximumCount 비교). */
	int spawnCount;

	ScriptEntity(String script, EscScript.Entity data, LevelLayout.Placement owner, float[] world, LevelData.PlacedVolume volume) {
		this.script = script;
		this.data = data;
		this.owner = owner;
		this.world = world;
		this.volume = volume;
		for (EscScript.Handler h : data.handlers()) {
			handlers.put(h.id(), h);
		}
	}

	public int type() {
		return data.type();
	}

	public String typeName() {
		return data.typeName();
	}

	public int id() {
		return data.id();
	}

	public String name() {
		return data.name();
	}

	/** 대상 선택 모드 (원본 0x4C, 원작 +0x46): 1 소유자, 2 보낸 쪽, 3 전체, 4 볼륨 안, 5 전역 이벤트. Data 타입에선 변수 수. */
	public int mode() {
		return data.param();
	}

	public List<Integer> targets() {
		return data.targets();
	}

	/** 이름 첫 '-' 앞 = Maya 그룹 이름 = ESC/ENV/go 이름. */
	public String group() {
		int i = data.name().indexOf('-');
		return i > 0 ? data.name().substring(0, i) : data.name();
	}

	public String ownerName() {
		return owner != null ? owner.instance().name() : null;
	}

	public float[] position() {
		return new float[]{world[12], world[13], world[14]};
	}

	public boolean hasSlot(String name) {
		return handlers.containsKey(EntitySlots.index(type(), name));
	}

	EscScript.Handler handler(int slot) {
		return handlers.get(slot);
	}

	@Override
	public String toString() {
		return "#" + id() + " " + typeName() + " '" + name() + "'";
	}
}
