package com.boltstorms.brainflood;

import com.badlogic.gdx.Game;

public class BrainFloodGame extends Game {
	@Override
	public void create() {
		setScreen(new GameScreen());
	}
}
