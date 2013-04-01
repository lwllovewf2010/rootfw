/*
 * This file is part of the RootFW Project: https://github.com/spazedog/rootfw
 *  
 * Copyright (c) 2013 Daniel Bergløv
 *
 * RootFW is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * RootFW is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public License
 * along with RootFW. If not, see <http://www.gnu.org/licenses/>
 */

package com.spazedog.rootfw.containers;

import java.util.ArrayList;

public class ShellCommand {
	private ArrayList<String[]> CMD = new ArrayList<String[]>();
	private ArrayList<Integer> RESULT = new ArrayList<Integer>();
	
	private static String[] BINARIES = {"busybox", "toolbox"};
	
	public static ShellCommand makeCompatibles(String argCommands) {
		return makeCompatibles(new String[] {argCommands}, null);
	}
	
	public static ShellCommand makeCompatibles(String[] argCommands) {
		return makeCompatibles(argCommands, null);
	}
	
	public static ShellCommand makeCompatibles(String argCommands, Integer[] argResults) {
		return makeCompatibles(new String[] {argCommands}, argResults);
	}
	
	public static ShellCommand makeCompatibles(String[] argCommands, Integer[] argResults) {
		ShellCommand command = new ShellCommand(0);
		
		for (int x=0; x < argCommands.length; x++) {
			for (int y=0; y < BINARIES.length; y++) {
				command.addCommand(argCommands[x].replaceAll("%binary", BINARIES[y]) + " 2>/dev/null");
			}
			
			command.addCommand(argCommands[x].replaceAll("%binary |%binary", "") + " 2>/dev/null");
		}
		
		if (argResults != null) {
			for (int i=0; i < argResults.length; i++) {
				command.addResult(argResults[i]);
			}
		}
		
		return command;
	}
	
	public static String[] getCompatibleBinaries() {
		return BINARIES;
	}
	
	public ShellCommand() {}
	
	public ShellCommand(Integer argResult) {
		RESULT.add(argResult);
	}
	
	public ShellCommand addCommand(String argCmd) {
		CMD.add(new String[] {argCmd});
		
		return this;
	}
	
	public ShellCommand addCommand(String[] argCmd) {
		CMD.add(argCmd);
		
		return this;
	}
	
	public ShellCommand addResult(Integer argResult) {
		RESULT.add(argResult);
		
		return this;
	}
	
	public Integer getCommandLength() {
		return CMD.size();
	}
	
	public Integer getResultLength() {
		return RESULT.size();
	}
	
	public String[] getCommand(Integer index) {
		return CMD.get(index);
	}
	
	public Integer getResult(Integer index) {
		return RESULT.get(index);
	}
}
