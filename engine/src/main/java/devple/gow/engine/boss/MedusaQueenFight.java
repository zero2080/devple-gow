package devple.gow.engine.boss;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Stages;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 메두사 여왕 (ATHN02D, AI1 062 "medusa queen", 37 문서 §3).
 * <pre>
 * 진입 볼륨 MAmagicIcon-EntVol01 → SpawnEnemy 62 (그룹 26) + MEWINTRO 컷신
 * DTre_062: 대상 정지 → 꼬리치기(2) / HP 비율 &gt; 0.975 → 공격 0..3 + 시선(20 MEWBEAMON, 대기 3초) / 아니면 회복(30, SELF_HP −50)
 * 시선: MEWBEAMON 0.63– SCR_EyeBeamOn(idx 2) … 다른 기술의 SCR_EyeBeamOff → 영웅 석화 (원작 0x199000 → Freeze 0x1E1778)
 * HP ≤ Circle 9800 (= 200 피해) → 잡기 원 → 영웅 ○ GRAB → 260 MAIMEWGRAB (일반 GrabSync): TimedPress 060 (스틱 반 바퀴)
 *   [0.25–0.45] / 061 (한 바퀴) [0.46–0.75], 실패 → 261 MAIMEWESCAPE, 성공 → 0.90 SELF_HP 200 + SCR_ExplodeDeath = 머리 뜯기
 * 1035(260) → MedusaRipZone-EventCombat1 (5.33초 뒤): LevelData0 = 메두사 사망, 메시지 141 "메두사의 시선을 획득" → 마법 보상
 * </pre>
 * 영웅 석화는 근사: 광선 원뿔(±20°, 600 단위) 안에서 0.5초 → 영웅 FREEZEPOSE(120), 스틱 흔들기 누적 또는 4초 → FROZENBREAKOUT(122).
 */
public final class MedusaQueenFight extends BossFight {

	public static final String LEVEL = "ATHN02D";
	static final int HERO_FREEZE = 120, HERO_BREAKOUT = 122;
	static final float BEAM_RANGE = 600f, BEAM_COS = 0.94f, FREEZE_AFTER = 0.5f, STONE_MAX = 4f, WIGGLE_NEED = 6f;
	private boolean beam;
	private float exposed, stone, wiggle;
	private boolean heroStone;
	private float lastStickX, lastStickY;
	private int demoStep;
	/** 석화 횟수 (테스트). */
	public int petrified;

	private final Path rom;

	MedusaQueenFight(Path rom, WadFile permk) throws IOException {
		super(LEVEL);
		this.rom = rom;
		kinds.put(62, BossKinds.load(Stages.wad(rom, LEVEL), permk, List.of(), "me3", 62, 62));
		silent.addAll(java.util.List.of(901));
		bossTemplates.add(62);
	}

	/**
	 * 영웅 쪽 maiMewGrab/maiMewEscape 는 ATHN02D 에 없고 R_MEW 의 외부 그룹 ANM_MAIMEW 에만 있다 (me3 = mew 와 같은 애니 크기 0x5A400).
	 * [추정: 원작은 앞 레벨의 R_MEW 슬롯이 남아 있음 — 미확인]
	 */
	@Override
	public List<Path> heroAnimWads() {
		Path p = Content.file(rom, "R_MEW.WAD");
		return java.nio.file.Files.exists(p) ? List.of(p) : List.of();
	}

	@Override
	protected void bind(EnemyFighter f, ScriptActor a) {
		f.brain().engage();
	}

	/** 메두사 여왕의 잡기는 일반 GrabSync(MAI* 같은 이름 애니)가 맡는다. */
	@Override
	public boolean claimsGrab(EnemyFighter target) {
		return false;
	}

	public boolean beamOn() {
		return beam;
	}

	public boolean heroPetrified() {
		return heroStone;
	}

	@Override
	protected void script(EnemyFighter f, CombatFile.Event e) {
		if (e.hash() == SCR_EYE_BEAM_ON) {
			beam = true;
		} else if (e.hash() == SCR_EYE_BEAM_OFF) {
			beam = false;
		}
	}

	@Override
	protected void tick(Input in, float dt) {
		EnemyFighter f = boss;
		Combatant hc = arena.heroCombat();
		if (f.hp <= 0) {
			beam = false;
			markDefeated();
			return;
		}
		int m = f.combat().active() ? f.combat().move().id() : -1;
		if (m != 20 && m != 22) {
			beam = false;
		}
		if (heroStone) {
			// 스틱 흔들기로 탈출 (/Player/Wiggle Meter Drain 2.0 은 감소량 [추정])
			float sx = in.leftX(), sy = in.leftY();
			wiggle += (float) Math.hypot(sx - lastStickX, sy - lastStickY);
			wiggle = Math.max(0, wiggle - 2f * dt);
			lastStickX = sx;
			lastStickY = sy;
			stone += dt;
			if (wiggle >= WIGGLE_NEED || stone >= STONE_MAX) {
				heroStone = false;
				hc.forceMove(HERO_BREAKOUT);
				setPhase("석화 탈출");
			} else if (!hc.active() || hc.move().id() != HERO_FREEZE) {
				hc.forceMove(HERO_FREEZE);
			}
			return;
		}
		if (beam && inBeam(f) && !hc.guarding()) {
			exposed += dt;
			if (exposed >= FREEZE_AFTER) {
				heroStone = true;
				petrified++;
				stone = wiggle = exposed = 0;
				hc.forceMove(HERO_FREEZE);
				setPhase("영웅 석화");
			}
		} else {
			exposed = Math.max(0, exposed - dt);
		}
		if (m == 260) {
			setPhase("머리 뜯기");
		} else if (m == 20 || m == 22) {
			setPhase("시선");
		} else if (m == 30) {
			setPhase("회복");
		} else if (f.grabbable()) {
			setPhase("잡기 가능");
		} else if (m >= 0) {
			setPhase("전투");
		}
	}

	private boolean inBeam(EnemyFighter f) {
		Vector3f d = new Vector3f(arena.heroCtl().position).sub(f.pos).setComponent(1, 0);
		float len = d.length();
		if (len > BEAM_RANGE || len < 1e-3f) {
			return len < 1e-3f;
		}
		float cos = (-(float) Math.sin(f.yaw) * d.x - (float) Math.cos(f.yaw) * d.z) / len;
		return cos >= BEAM_COS;
	}

	@Override
	protected void beforeSpawnDemo(float dt) {
		if (demoTime > 1 && demoStep == 0) {
			demoStep = -1;
			placeHero(pointIn("MAmagicIcon-EntVol01-EntrySensor1", 960));
		}
	}

	/** 데모: 정면 150 → 시선(20) 석화 → 4초 뒤 탈출 → HP 9700 (회복·잡기 원) → ○ 잡기 → 스틱 회전 자동 → 머리 뜯기. */
	@Override
	protected void demo(float dt) {
		EnemyFighter f = boss;
		var c = arena.heroCtl();
		int m = f.combat().active() ? f.combat().move().id() : -1;
		if (demoStep <= 0) {
			demoStep = 1;
			demoTime = 0;
		} else if (demoStep == 1 && demoTime > 1.5f) {
			demoStep = 5; // 컷신 끝 Goto magicPlace 뒤에 배치
			c.position.set(f.pos.x - (float) Math.sin(f.yaw) * 150, f.pos.y + 20, f.pos.z - (float) Math.cos(f.yaw) * 150);
			c.prevPosition.set(c.position);
		} else if (demoStep == 5 && demoTime > 3 && m < 0) {
			demoStep = 2;
			f.combat().forceMove(20);
		} else if (demoStep == 2 && demoTime > 9) {
			demoStep = 3;
			f.hp = Math.min(f.hp, 9700);
		} else if (demoStep == 3 && demoTime > 12 && f.grabbable() && !heroStone && m != 20 && m != 22) {
			demoStep = 4;
			c.position.set(f.pos.x - (float) Math.sin(f.yaw) * 40, c.position.y, f.pos.z - (float) Math.cos(f.yaw) * 40);
			c.prevPosition.set(c.position);
			contact(200, 0);
		}
	}
}
