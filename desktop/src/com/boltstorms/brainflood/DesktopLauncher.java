package com.boltstorms.brainflood;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

public class DesktopLauncher {
	public static void main (String[] arg) {
		Lwjgl3ApplicationConfiguration config =
				new Lwjgl3ApplicationConfiguration();

		// Simulate Android portrait screen
		config.setWindowedMode(540, 960);

		config.setForegroundFPS(60);
		config.setTitle("BrainFlood");

		new Lwjgl3Application(new BrainFloodGame(), config);
	}
}
