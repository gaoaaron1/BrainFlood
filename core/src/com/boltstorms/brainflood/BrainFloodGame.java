package com.boltstorms.brainflood;

import com.badlogic.gdx.Game;
import com.boltstorms.brainflood.screens.GameScreen;

public class BrainFloodGame extends Game {
	@Override
	public void create() {
		setScreen(new GameScreen());
	}
}
