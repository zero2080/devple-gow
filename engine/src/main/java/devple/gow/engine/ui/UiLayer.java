package devple.gow.engine.ui;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.core.Engine;
import devple.gow.engine.render.Texture;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FLP 무비 하나 = 플레이어 + 렌더러 + 공유 변수. 게임 씬이 소유하고 update/render 를 위임한다.
 * <pre>
 *   UiLayer hud = UiLayer.load(rom.resolve("R_PERMK.WAD"), "FLP_HUDK", null);
 *   hud.variables().set("PS2_HealthMeter_Value", 80).event("PS2_MeterBar_Event", 1);
 *   hud.update(engine);  // 고정 틱
 *   hud.render(engine);  // 3D 위에 덮어 그림
 * </pre>
 */
public final class UiLayer implements AutoCloseable {

	private final WadAssets assets;
	private final boolean ownsAssets;
	private final FlpPlayer player;
	private final FlpRenderer renderer;
	private final UiVariables vars;
	private Texture white;
	/** 패드 입력을 FLP 에 전달할지 (HUD 가 메뉴를 열지 않았을 때 게임이 끌 수 있음). */
	public boolean acceptInput = true;

	public UiLayer(WadAssets assets, boolean ownsAssets, String flpName, MessageTable messages, UiVariables vars) {
		this.assets = assets;
		this.ownsAssets = ownsAssets;
		this.vars = vars;
		FlpFile flp = new FlpFile(findFlp(assets.wad(), flpName).data());
		this.player = new FlpPlayer(flp, vars, messages);
		this.renderer = new FlpRenderer(flp, assets, "MDL_" + flpName.substring(flpName.indexOf('_') + 1));
	}

	/**
	 * WAD 에서 FLP 를 연다. 메시지 테이블이 없으면(R_SHELLK 등) 같은 폴더의 R_PERMK.WAD 에서 읽는다.
	 * 메모리: 글로벌 변수는 vars 가 null 이면 새로 만든다.
	 */
	public static UiLayer load(Path wad, String flpName, UiVariables vars) throws IOException {
		WadAssets a = new WadAssets(wad);
		MessageTable msgs = MessageTable.fromWad(a.wad()).orElse(null);
		Path perm = wad.resolveSibling("R_PERMK.WAD");
		if (msgs == null && Files.exists(perm)) {
			msgs = MessageTable.fromWad(WadFile.load(perm)).orElse(null);
		}
		return new UiLayer(a, true, flpName, msgs, vars != null ? vars : new UiVariables());
	}

	private static WadTag findFlp(WadFile wad, String name) {
		for (WadTag t : wad.tags()) {
			if (t.hasData() && t.name().equals(name) && t.magic() == FlpFile.MAGIC) {
				return t;
			}
		}
		throw new IllegalArgumentException("FLP 없음: " + name);
	}

	public FlpPlayer player() {
		return player;
	}

	public UiVariables variables() {
		return vars;
	}

	public FlpRenderer renderer() {
		return renderer;
	}

	/** 고정 틱: 패드 → FLP 마스크, 한 프레임 진행. */
	public void update(Engine e) {
		player.pad(acceptInput ? UiPad.mask(e.input()) : 0);
		player.tick();
	}

	/** 현재 프레임버퍼 위에 UI 를 덮어 그린다. */
	public void render(Engine e) {
		if (white == null) {
			white = Texture.white();
		}
		renderer.render(player, e.renderer().basic(), white, e.window().width(), e.window().height());
	}

	@Override
	public void close() {
		renderer.close();
		if (white != null) {
			white.close();
		}
		if (ownsAssets) {
			assets.close();
		}
	}
}
