// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj;

import micropolisj.gui.MainWindow;

import javax.swing.SwingUtilities;

class Micropolis
{
	private static String fileToLoad;

	private static void createAndShowGUI()
	{
		MainWindow win = new MainWindow();
		win.setVisible(true);
		if (fileToLoad != null) {
			try {
				micropolisj.engine.Micropolis engine = new micropolisj.engine.Micropolis();
				engine.load(new java.io.File(fileToLoad));
				win.setEngine(engine);
			} catch (Exception e) {
				e.printStackTrace();
				win.doNewCity(true);
			}
		} else {
			win.doNewCity(true);
		}
	}

	public static void main(String[] args)
	{
		if (args.length > 0) {
			fileToLoad = args[0];
		}
		SwingUtilities.invokeLater(Micropolis::createAndShowGUI);
	}
}
