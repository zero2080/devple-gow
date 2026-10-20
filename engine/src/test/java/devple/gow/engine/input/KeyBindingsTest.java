package devple.gow.engine.input;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.GameConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** keys.properties 해석 (49 문서 §6). GLFW 네이티브 없이. */
class KeyBindingsTest {

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		Content.install(null);
	}

	private static Properties props(String text) throws IOException {
		Properties p = new Properties();
		p.load(new StringReader(text));
		return p;
	}

	@Test
	void defaultsMatchOriginalLayout() {
		KeyBindings k = KeyBindings.defaults();
		assertArrayEquals(new int[]{GLFW_KEY_K}, k.keys(PadButton.CROSS));
		assertArrayEquals(new int[]{GLFW_KEY_J}, k.keys(PadButton.SQUARE));
		assertArrayEquals(new int[]{GLFW_KEY_I}, k.keys(PadButton.TRIANGLE));
		assertArrayEquals(new int[]{GLFW_KEY_L}, k.keys(PadButton.CIRCLE));
		assertArrayEquals(new int[]{GLFW_KEY_ENTER}, k.keys(PadButton.START));
		assertArrayEquals(new int[]{GLFW_KEY_RIGHT_SHIFT}, k.keys(PadButton.R3));
		assertArrayEquals(new int[]{GLFW_KEY_UP}, k.keys(PadButton.UP));
		assertArrayEquals(new int[]{GLFW_KEY_UP}, k.keys(KeyBindings.Stick.STICK_R_UP));
		assertArrayEquals(new int[]{GLFW_KEY_W}, k.keys(KeyBindings.Stick.STICK_L_UP));
		assertArrayEquals(new int[]{GLFW_KEY_D}, k.keys(KeyBindings.Stick.STICK_L_RIGHT));
		assertArrayEquals(new int[]{GLFW_GAMEPAD_BUTTON_A}, k.pad(PadButton.CROSS));
		assertArrayEquals(new int[]{KeyBindings.PAD_LEFT_TRIGGER}, k.pad(PadButton.L2));
		for (PadButton b : PadButton.values()) {
			assertEquals(1, k.keys(b).length, b.name());
			assertEquals(1, k.pad(b).length, b.name());
		}
	}

	@Test
	void parsesMultipleKeysSticksAndPad() throws IOException {
		KeyBindings k = KeyBindings.parse(props("""
				CROSS=K, space
				square=KEY_F1,KP_5,0
				STICK_L_UP=UP
				SELECT=
				pad.CROSS=B
				pad.L2=LEFT_BUMPER,RIGHT_TRIGGER
				"""));
		assertArrayEquals(new int[]{GLFW_KEY_K, GLFW_KEY_SPACE}, k.keys(PadButton.CROSS));
		assertArrayEquals(new int[]{GLFW_KEY_F1, GLFW_KEY_KP_5, GLFW_KEY_0}, k.keys(PadButton.SQUARE));
		assertArrayEquals(new int[]{GLFW_KEY_UP}, k.keys(KeyBindings.Stick.STICK_L_UP));
		assertArrayEquals(new int[0], k.keys(PadButton.SELECT));
		assertArrayEquals(new int[]{GLFW_GAMEPAD_BUTTON_B}, k.pad(PadButton.CROSS));
		assertArrayEquals(new int[]{GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, KeyBindings.PAD_RIGHT_TRIGGER}, k.pad(PadButton.L2));
		assertArrayEquals(new int[]{GLFW_KEY_L}, k.keys(PadButton.CIRCLE)); // 안 적은 것은 기본
	}

	@Test
	void unknownNamesWarnAndKeepDefault() throws IOException {
		KeyBindings k = KeyBindings.parse(props("""
				CROSS=NOPE
				CIRCLE=BOGUS,X
				JUMP=K
				pad.SQUARE=Z9
				"""));
		assertArrayEquals(new int[]{GLFW_KEY_K}, k.keys(PadButton.CROSS));
		assertArrayEquals(new int[]{GLFW_KEY_X}, k.keys(PadButton.CIRCLE));
		assertArrayEquals(new int[]{GLFW_GAMEPAD_BUTTON_X}, k.pad(PadButton.SQUARE));
		assertEquals(-1, KeyBindings.keyCode("NOPE"));
		assertEquals(GLFW_KEY_LEFT_SHIFT, KeyBindings.keyCode("left_shift"));
		assertEquals(GLFW_KEY_F25, KeyBindings.keyCode("F25"));
		assertEquals(GLFW_GAMEPAD_BUTTON_DPAD_LEFT, KeyBindings.padCode("DPAD_LEFT"));
	}

	@Test
	void layersMergeFromContent() throws IOException {
		Path a = Files.createDirectories(tmp.resolve("a")), user = Files.createDirectories(tmp.resolve("config"));
		Files.writeString(a.resolve(KeyBindings.FILE), "CROSS=SPACE\nCIRCLE=O\n");
		Files.writeString(user.resolve(KeyBindings.FILE), "CROSS=ENTER\n");
		Content.install(Content.of(new GameConfig(new Properties()), List.of(a, user)));
		KeyBindings k = KeyBindings.current();
		assertArrayEquals(new int[]{GLFW_KEY_ENTER}, k.keys(PadButton.CROSS));
		assertArrayEquals(new int[]{GLFW_KEY_O}, k.keys(PadButton.CIRCLE));
		Content.install(Content.of(new GameConfig(new Properties()), List.of()));
		assertArrayEquals(new int[]{GLFW_KEY_K}, KeyBindings.current().keys(PadButton.CROSS));
	}
}
