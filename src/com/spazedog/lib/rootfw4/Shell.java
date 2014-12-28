/*
 * This file is part of the RootFW Project: https://github.com/spazedog/rootfw
 *  
 * Copyright (c) 2015 Daniel Bergløv
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

package com.spazedog.lib.rootfw4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import android.os.Bundle;
import android.util.Log;

import com.spazedog.lib.rootfw4.ShellStream.OnStreamListener;
import com.spazedog.lib.rootfw4.containers.Data;

/**
 * This class is a front-end to {@link ShellStream} which makes it easier to work
 * with normal shell executions. If you need to execute a consistent command (one that never ends), 
 * you should work with {@link ShellStream} directly. 
 */
public class Shell {
	public static final String TAG = Common.TAG + ".Shell";
	
	protected static Set<Shell> mInstances = Collections.newSetFromMap(new WeakHashMap<Shell, Boolean>());
	protected static Map<String, String> mBinaries = new HashMap<String, String>();
	
	protected Set<OnShellBroadcastListener> mBroadcastRecievers = Collections.newSetFromMap(new WeakHashMap<OnShellBroadcastListener, Boolean>());
	protected Set<OnShellConnectionListener> mConnectionRecievers = new HashSet<OnShellConnectionListener>();
	
	protected Object mLock = new Object();
	protected ShellStream mStream;
	protected Boolean mIsConnected = false;
	protected Boolean mIsRoot = false;
	protected List<String> mOutput = null;
	protected Integer mResultCode = 0;
	protected Integer mShellTimeout = 1500;
	protected Set<Integer> mResultCodes = new HashSet<Integer>();
	
	/**
	 * This interface is used internally across utility classes.
	 */
	public static interface OnShellBroadcastListener {
		public void onShellBroadcast(String key, Bundle data);
	}
	
	/**
	 * This interface is for use with {@link Shell#executeAsync(String[], OnShellResultListener)}.
	 */
	public static interface OnShellResultListener {
		/**
		 * Called when an asynchronous execution has finished.
		 * 
		 * @param result
		 *     The result from the asynchronous execution
		 */
		public void onShellResult(Result result);
	}
	
	/**
	 * This interface is used to get information about connection changes.
	 */
	public static interface OnShellConnectionListener {
		/**
		 * Called when the connection to the shell is lost.
		 */
		public void onShellDisconnect();
	}
	
	/**
	 * This class is used to store the result from shell executions. 
	 * It extends the {@link Data} class.
	 */
	public static class Result extends Data<Result> {
		private Integer mResultCode;
		private Integer[] mValidResults;
		private Integer mCommandNumber;
		
		public Result(String[] lines, Integer result, Integer[] validResults, Integer commandNumber) {
			super(lines);
			
			mResultCode = result;
			mValidResults = validResults;
			mCommandNumber = commandNumber;
		}

		/**
		 * Get the result code from the shell execution.
		 */
		public Integer getResultCode() {
			return mResultCode;
		}

		/**
		 * Compare the result code with {@link Shell#addResultCode(Integer)} to determine 
		 * whether or not the execution was a success. 
		 */
		public Boolean wasSuccessful() {
			for (int i=0; i < mValidResults.length; i++) {
				if ((int) mValidResults[i] == (int) mResultCode) {
					return true;
				}
			}
			
			return false;
		}
		
		/**
		 * Get the command number that produced a successful result. 
		 */
		public Integer getCommandNumber() {
			return mCommandNumber;
		}
	}
	
	/**
	 * A class containing automatically created shell attempts and links to both {@link Shell#executeAsync(String[], Integer[], OnShellResultListener)} and {@link Shell#execute(String[], Integer[])} <br /><br />
	 * 
	 * All attempts are created based on {@link Common#BINARIES}. <br /><br />
	 * 
	 * Example: String("ls") would become String["ls", "busybox ls", "toolbox ls"] if {@link Common#BINARIES} equals String[null, "busybox", "toolbox"].<br /><br />
	 * 
	 * You can also apply the keyword %binary if you need to apply the binaries to more than the beginning of a command. <br /><br />
	 * 
	 * Example: String("(%binary test -d '%binary pwd') || exit 1") would become String["(test -d 'pwd') || exit 1", "(busybox test -d 'busybox pwd') || exit 1", "(toolbox test -d 'toolbox pwd') || exit 1"]
	 * 
	 * @see Shell#createAttempts(String)
	 */
	public class Attempts {
		protected String[] mAttempts;
		protected Integer[] mResultCodes;
		
		protected Attempts(String command) {
			if (command != null) {
				Integer pos = 0;
				mAttempts = new String[ Common.BINARIES.length ];
				
				for (String binary : Common.BINARIES) {
					if (command.contains("%binary ")) {
						mAttempts[pos] = command.replaceAll("%binary ", (binary != null && binary.length() > 0 ? binary + " " : ""));
						
					} else {
						mAttempts[pos] = (binary != null && binary.length() > 0 ? binary + " " : "") + command;
					}
					
					pos += 1;
				}
			}
		}
		
		public Attempts setResultCodes(Integer... resultCodes) {
			mResultCodes = resultCodes; return this;
		}
		
		public Result execute() {
			return Shell.this.execute(mAttempts, null);
		}
		
		public void executeAsync(OnShellResultListener listener) {
			Shell.this.executeAsync(mAttempts, null, listener);
		}
	}
	
	/**
	 * Establish a {@link ShellStream} connection.
	 * 
	 * @param requestRoot
	 *     Whether or not to request root privileges for the shell connection
	 */
	public Shell(Boolean requestRoot) {
		mResultCodes.add(0);
		mIsRoot = requestRoot;
		
		/*
		 * Kutch superuser daemon mode sometimes has problems connecting the first time.
		 * So we will give it two tries before giving up.
		 */
		for (int i=0; i < 2; i++) {
			if(Common.DEBUG)Log.d(TAG, "Construct: Running connection attempt number " + (i+1));
			
			mStream = new ShellStream(requestRoot, new OnStreamListener() {
				@Override
				public void onStreamStart() {
					if(Common.DEBUG)Log.d(TAG, "onStreamStart: ...");
					
					mOutput = new ArrayList<String>();
				}

				@Override
				public void onStreamInput(String outputLine) {
					if(Common.DEBUG)Log.d(TAG, "onStreamInput: " + (outputLine != null ? (outputLine.length() > 50 ? outputLine.substring(0, 50) + " ..." : outputLine) : "NULL"));
					
					mOutput.add(outputLine);
				}

				@Override
				public void onStreamStop(Integer resultCode) {
					if(Common.DEBUG)Log.d(TAG, "onStreamStop: " + resultCode);
					
					mResultCode = resultCode;
				}

				@Override
				public void onStreamDied() {
					if(Common.DEBUG)Log.d(TAG, "onStreamDied: The stream has been closed");
					
					if (mIsConnected) {
						if(Common.DEBUG)Log.d(TAG, "onStreamDied: The stream seams to have died, reconnecting");
						
						mStream = new ShellStream(mIsRoot, this);
						
						if (mStream.isActive()) {
							Result result = execute("echo connected");
							
							mIsConnected = result != null && "connected".equals(result.getLine());
							
						} else {
							if(Common.DEBUG)Log.d(TAG, "onStreamDied: Could not reconnect");
							
							mIsConnected = false;
						}
					}
					
					if (!mIsConnected) {
						for (OnShellConnectionListener reciever : mConnectionRecievers) {
							reciever.onShellDisconnect();
						}
					}
				}
			});
			
			if (mStream.isActive()) {
				Result result = execute("echo connected");
				
				mIsConnected = result != null && "connected".equals(result.getLine());
				
				if (mIsConnected) {
					if(Common.DEBUG)Log.d(TAG, "Construct: Connection has been established");
					
					mInstances.add(this); break;
				}
			}
		}
	}
	
	/**
	 * Execute a shell command.
	 * 
	 * @see Shell#execute(String[], Integer[])
	 * 
	 * @param command
	 *     The command to execute
	 */
	public Result execute(String command) {
		return execute(new String[]{command}, null);
	}
	
	/**
	 * Execute a range of commands until one is successful.
	 * 
	 * @see Shell#execute(String[], Integer[])
	 * 
	 * @param commands
	 *     The commands to try
	 */
	public Result execute(String[] commands) {
		return execute(commands, null);
	}
	
	/**
	 * Execute a range of commands until one is successful.<br /><br />
	 * 
	 * Android shells differs a lot from one another, which makes it difficult to program shell scripts for. 
	 * This method can help with that by trying different commands until one works. <br /><br />
	 * 
	 * <code>Shell.execute( new String(){"cat file", "toolbox cat file", "busybox cat file"} );</code><br /><br />
	 * 
	 * Whether or not a command was successful, depends on {@link Shell#addResultCode(Integer)} which by default only contains '0'. 
	 * The command number that was successful can be checked using {@link Result#getCommandNumber()}.
	 * 
	 * @param commands
	 *     The commands to try
	 *     
	 * @param
	 *     Result Codes representing successful execution. These will be temp. merged with {@link Shell#addResultCode(Integer)}. 
	 */
	public Result execute(String[] commands, Integer[] resultCodes) {
		synchronized(mLock) {
			if (mStream.waitFor(mShellTimeout)) {
				Integer cmdCount = 0;
				Set<Integer> codes = new HashSet<Integer>(mResultCodes);
				
				if (resultCodes != null) {
					Collections.addAll(codes, resultCodes);
				}
				
				for (String command : commands) {
					if(Common.DEBUG)Log.d(TAG, "execute: Executing the command '" + command + "'");
					
					mStream.execute(command);
					
					if(!mStream.waitFor(mShellTimeout)) {
						/*
						 * Something is wrong, reconnect to the shell.
						 */
						mStream.destroy();
						
						return null;
					}
					
					if(Common.DEBUG)Log.d(TAG, "execute: The command finished with the result code '" + mResultCode + "'");
					
					if (codes.contains(mResultCode)) {
						break;
					}
					
					cmdCount += 1;
				}
				
				return new Result(mOutput.toArray(new String[mOutput.size()]), mResultCode, codes.toArray(new Integer[codes.size()]), cmdCount);
			}
			
			return null;
		}
	}
	
	/**
	 * Execute a shell command asynchronous.
	 * 
	 * @see Shell#executeAsync(String[], Integer[], OnShellResultListener)
	 * 
	 * @param command
	 *     The command to execute
	 * 
	 * @param listener
	 *     A {@link Shell.OnShellResultListener} callback instance
	 */
	public void executeAsync(String command, OnShellResultListener listener) {
		executeAsync(new String[]{command}, null, listener);
	}
	
	/**
	 * Execute a range of commands asynchronous until one is successful.
	 * 
	 * @see Shell#executeAsync(String[], Integer[], OnShellResultListener)
	 * 
	 * @param commands
	 *     The commands to try
	 * 
	 * @param listener
	 *     A {@link Shell.OnShellResultListener} callback instance
	 */
	public synchronized void executeAsync(String[] commands, OnShellResultListener listener) {
		executeAsync(commands, null, listener);
	}
	
	/**
	 * Execute a range of commands asynchronous until one is successful.
	 * 
	 * @see Shell#execute(String[], Integer[])
	 * 
	 * @param commands
	 *     The commands to try
	 *     
	 * @param
	 *     Result Codes representing successful execution. These will be temp. merged with {@link Shell#addResultCode(Integer)}.
	 * 
	 * @param listener
	 *     A {@link Shell.OnShellResultListener} callback instance
	 */
	public synchronized void executeAsync(final String[] commands, final Integer[] resultCodes, final OnShellResultListener listener) {
		if(Common.DEBUG)Log.d(TAG, "executeAsync: Starting an async shell execution");
		
		/*
		 * If someone execute more than one async task after another, and use the same listener, 
		 * we could end up getting the result in the wrong order. We need to make sure that each Thread is started in the correct order. 
		 */
		final Object lock = new Object();
		
		new Thread() {
			@Override
			public void run() {
				synchronized (lock) {
					lock.notifyAll();
				}
				
				synchronized(mLock) {
					Result result = Shell.this.execute(commands, resultCodes);
					listener.onShellResult(result);
				}
			}
			
		}.start();
		
		/*
		 * Do not exit this method, until the Thread is started. 
		 */
		synchronized (lock) {
			try {
				lock.wait();
				
			} catch (InterruptedException e) {}
		}
	}
	
	/**
	 * For internal usage
	 */
	public void sendBroadcast(String key, Bundle data) {
		for (Shell instance : mInstances) {
			instance.broadcastReciever(key, data);
		}
	}
	
	/**
	 * For internal usage
	 */
	protected void broadcastReciever(String key, Bundle data) {
		for (OnShellBroadcastListener recievers : mBroadcastRecievers) {
			recievers.onShellBroadcast(key, data);
		}
	}
	
	/**
	 * For internal usage
	 */
	public void addBroadcastListener(OnShellBroadcastListener listener) {
		mBroadcastRecievers.add(listener);
	}
	
	/**
	 * Add a shell connection listener. This callback will be invoked whenever the connection to
	 * the shell changes. 
	 * 
	 * @param listener
	 *     A {@link Shell.OnShellConnectionListener} callback instance
	 */
	public void addShellConnectionListener(OnShellConnectionListener listener) {
		mConnectionRecievers.add(listener);
	}
	
	/**
	 * Remove a shell connection listener from the stack. 
	 * 
	 * @param listener
	 *     A {@link Shell.OnShellConnectionListener} callback instance
	 */
	public void removeShellConnectionListener(OnShellConnectionListener listener) {
		mConnectionRecievers.remove(listener);
	}
	
	/**
	 * Check whether or not root was requested for this shell.
	 */
	public Boolean isRoot() {
		return mIsRoot;
	}
	
	/**
	 * Check whether or not a shell connection was established. 
	 */
	public Boolean isConnected() {
		return mIsConnected;
	}
	
	/**
	 * Get the current shell execution timeout. 
	 * This is the time in milliseconds from which an execution is killed in case it has stalled. 
	 */
	public Integer getTimeout() {
		return mShellTimeout;
	}
	
	/**
	 * Change the shell execution timeout. This should be in milliseconds. 
	 * If this is set to '0', there will be no timeout. 
	 */
	public void setTimeout(Integer timeout) {
		if (timeout >= 0) {
			mShellTimeout = timeout;
		}
	}
	/**
	 * Add another result code that represent a successful execution. By default only '0' is used, since 
	 * most shell commands uses '0' for success and '1' for error. But some commands uses different values, like 'cat' 
	 * that uses '130' as success when piping content. 
	 * 
	 * @param resultCode
	 *     The result code to add to the stack
	 */
	public void addResultCode(Integer resultCode) {
		mResultCodes.add(resultCode);
	}
	
	/**
	 * Remove a result code from the stack.
	 * 
	 * @see Shell#addResultCode(Integer)
	 * 
	 * @param resultCode
	 *     The result code to remove from the stack
	 */
	public void removeResultCode(Integer resultCode) {
		mResultCodes.remove(resultCode);
	}
	
	/**
	 * Reset the stack containing result codes and set it back to default only containing '0'.
	 * 
	 * @see Shell#addResultCode(Integer)
	 */
	public void resetResultCodes() {
		mResultCodes.clear();
		mResultCodes.add(0);
	}
	
	/**
	 * Close the shell connection using 'exit 0' if possible, or by force and release all data stored in this instance. 
	 */
	public void destroy() {
		if (mStream != null) {
			mIsConnected = false;
			
			if (mStream.isRunning() || !mStream.isActive()) {
				if(Common.DEBUG)Log.d(TAG, "destroy: Destroying the stream");
				
				mStream.destroy();
				
			} else {
				if(Common.DEBUG)Log.d(TAG, "destroy: Making a clean exit on the stream");
				
				mStream.execute("exit 0");
			}
			
			mStream = null;
			mInstances.remove(this);
			mBroadcastRecievers.clear();
		}
	}
	
	/**
	 * Locate whichever toolbox in {@value Common#BINARIES} that supports a specific command.<br /><br />
	 * 
	 * Example: String("cat") might return String("busybox cat") or String("toolbox cat")
	 * 
	 * @param bin
	 *     The command to check
	 */
	public String getBinary(String bin) {
		if (!mBinaries.containsKey(bin)) {
			for (String toolbox : Common.BINARIES) {
				String cmd = toolbox != null && toolbox.length() > 0 ? toolbox + " " + bin : bin;
				Result result = execute( cmd + " -h" );
					
				if (result != null) {
					String line = result.getLine();
					
					if (!line.endsWith("not found") && !line.endsWith("such tool")) {
						mBinaries.put(bin, cmd); break;
					}
				}
			}
		}
		
		return mBinaries.get(bin);
	}
	
	/**
	 * Create a new instance of {@link Attempts}
	 * 
	 * @param command
	 *     The command to convert into multiple attempts
	 */
	public Attempts createAttempts(String command) {
		if (command != null) {
			return new Attempts(command);
		}
		
		return null;
	}
}
