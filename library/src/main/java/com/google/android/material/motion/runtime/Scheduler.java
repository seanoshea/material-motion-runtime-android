/*
 * Copyright (C) 2016 The Material Motion Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.material.motion.runtime;

import android.support.annotation.IntDef;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;
import com.google.android.material.motion.runtime.ChoreographerCompat.FrameCallback;
import com.google.android.material.motion.runtime.Transaction.PlanInfo;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A Scheduler accepts {@link Transaction Transactions} and creates {@link Performer Performers}.
 * The Scheduler generates relevant events for Performers and {@link StateListener listeners} and
 * monitors {@link State}.
 *
 * <p>
 * Commit Transactions to this Scheduler by calling {@link #commitTransaction(Transaction)}.
 * A Scheduler ensures that only one {@link Performer} instance is created for each type of
 * Performer required by a target. This allows multiple {@link Plan Plans} to affect a single
 * Performer instance. The Performers can then maintain state across multiple Plans.
 *
 * <p>
 * Query the State of this Scheduler by calling {@link #getState()}.
 * A Scheduler is active if any of its Performers are active. To listen for state changes, attach
 * listeners via {@link #addStateListener(StateListener)}.
 *
 * <p>
 * This Scheduler correctly handles all the interfaces defined in {@link Performer}.
 *
 * @see <a href="https://material-motion.gitbooks.io/material-motion-starmap/content/specifications/runtime/scheduler.html">The Scheduler specification</a>
 */
public final class Scheduler {

  /**
   * A listener that receives callbacks when the {@link Scheduler}'s {@link State} changes.
   */
  public interface StateListener {

    /**
     * Notifies the {@link State} change of the {@link Scheduler}.
     */
    void onStateChange(Scheduler scheduler, @State int newState);
  }

  /**
   * An idle {@link State}, signifying no active {@link Performer Performers}.
   */
  public static final int IDLE = 0;
  /**
   * An active {@link State}, signifying one or more active {@link Performer Performers}.
   */
  public static final int ACTIVE = 1;

  /**
   * The state of a {@link Scheduler}.
   */
  @IntDef({IDLE, ACTIVE})
  @Retention(RetentionPolicy.SOURCE)
  public @interface State {}

  private static final String TAG = "Scheduler";
  /**
   * Flag for detailed state bitmask specifying that the activity originates from a
   * {@link com.google.android.material.motion.runtime.Performer.ManualPerformance}.
   */
  static final int MANUAL_DETAILED_STATE_FLAG = 1 << 0;
  /**
   * Flag for detailed state bitmask specifying that the activity originates from a
   * {@link com.google.android.material.motion.runtime.Performer.ContinuousPerformance}.
   */
  static final int CONTINUOUS_DETAILED_STATE_FLAG = 1 << 1;

  private final CopyOnWriteArraySet<StateListener> listeners = new CopyOnWriteArraySet<>();
  private final ChoreographerCompat choreographer = ChoreographerCompat.getInstance();
  private final ManualPerformanceFrameCallback manualPerformanceFrameCallback =
      new ManualPerformanceFrameCallback();

  private final SimpleArrayMap<Object, TargetScope> targets = new SimpleArrayMap<>();
  private final Set<TargetScope> activeManualPerformanceTargets = new HashSet<>();
  private final Set<TargetScope> activeContinuousPerformanceTargets = new HashSet<>();

  /**
   * @return The current {@link State} of this Scheduler.
   */
  @State
  public int getState() {
    return getDetailedState() == 0 ? IDLE : ACTIVE;
  }

  /**
   * Returns the detailed state of this Scheduler, which includes information on the type of
   * {@link Performer} that affects this state.
   *
   * @return A bitmask representing the detailed state of this Scheduler.
   */
  private int getDetailedState() {
    int state = 0;
    if (!activeManualPerformanceTargets.isEmpty()) {
      state |= MANUAL_DETAILED_STATE_FLAG;
    }
    if (!activeContinuousPerformanceTargets.isEmpty()) {
      state |= CONTINUOUS_DETAILED_STATE_FLAG;
    }
    return state;
  }

  /**
   * Adds a {@link StateListener} to be notified of this Scheduler's {@link State} changes.
   */
  public void addStateListener(StateListener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  /**
   * Removes a {@link StateListener} from this Scheduler's {@link State} changes.
   */
  public void removeStateListener(StateListener listener) {
    listeners.remove(listener);
  }

  /**
   * Commits the given {@link Transaction}. Each {@link PlanInfo} is committed in the context of
   * its target, called a {@link TargetScope}. Each TargetScope ensures that only one instance of a
   * specific type of Performer is created.
   * @deprecated 2.0.0. Plans should be added directly to the Scheduler instead of using Transactions. <br />
   *              This will be removed in the next version <br />
   *              use {@link com.google.android.material.motion.runtime.Scheduler#addPlan(Plan, Object)} on the Scheduler instead
   */
  @Deprecated
  public void commitTransaction(Transaction transaction) {
    List<PlanInfo> plans = transaction.getPlans();
    for (int i = 0, count = plans.size(); i < count; i++) {
      PlanInfo plan = plans.get(i);
      getTargetScope(plan.target).commitPlan(plan);
    }
  }

  /**
   * Adds a plan to this scheduler.
   * @param plan the {@link Plan} to add to the scheduler.
   * @param target the target on which the plan will operate.
   */
  public void addPlan(Plan plan, Object target) {
    PlanInfo planInfo = new PlanInfo();
    planInfo.target = target;
    planInfo.plan = plan.clone();
    getTargetScope(target).commitPlan(planInfo);
  }

  private TargetScope getTargetScope(Object target) {
    TargetScope targetScope = targets.get(target);

    if (targetScope == null) {
      targetScope = new TargetScope(this);
      targets.put(target, targetScope);
    }

    return targetScope;
  }

  /**
   * Notifies the Scheduler that a {@link TargetScope}'s detailed state may or may not have changed.
   */
  void setTargetState(TargetScope target, int targetDetailedState) {
    int oldDetailedState = getDetailedState();

    if (isSet(targetDetailedState, MANUAL_DETAILED_STATE_FLAG)) {
      activeManualPerformanceTargets.add(target);
    } else {
      activeManualPerformanceTargets.remove(target);
    }

    if (isSet(targetDetailedState, CONTINUOUS_DETAILED_STATE_FLAG)) {
      activeContinuousPerformanceTargets.add(target);
    } else {
      activeContinuousPerformanceTargets.remove(target);
    }

    int newDetailedState = getDetailedState();
    if (oldDetailedState != newDetailedState) {
      onDetailedStateChange(oldDetailedState, newDetailedState);
    }
  }

  private void onDetailedStateChange(int oldDetailedState, int newDetailedState) {
    if (changed(oldDetailedState, newDetailedState, MANUAL_DETAILED_STATE_FLAG)) {
      if (isSet(newDetailedState, MANUAL_DETAILED_STATE_FLAG)) {
        Log.d(TAG, "Manual performance TargetScopes now active.");
        manualPerformanceFrameCallback.start();
      } else {
        Log.d(TAG, "Manual performance TargetScopes now idle.");
        manualPerformanceFrameCallback.stop();
      }
    }
    if (changed(oldDetailedState, newDetailedState, CONTINUOUS_DETAILED_STATE_FLAG)) {
      if (isSet(newDetailedState, CONTINUOUS_DETAILED_STATE_FLAG)) {
        Log.d(TAG, "Continuous performance TargetScopes now active.");
      } else {
        Log.d(TAG, "Continuous performance TargetScopes now idle.");
      }
    }

    if ((oldDetailedState == 0) != (newDetailedState == 0)) {
      @State int state = newDetailedState == 0 ? IDLE : ACTIVE;
      Log.d(TAG, "Scheduler state now: " + state);
      for (StateListener listener : listeners) {
        listener.onStateChange(this, state);
      }
    }
  }

  /**
   * Returns whether a flag bit on one bitmask differs from that on another bitmask.
   *
   * @param oldDetailedState The old bitmask.
   * @param newDetailedState The new bitmask.
   * @param flag The flag bit to check for a change.
   */
  private static boolean changed(int oldDetailedState, int newDetailedState, int flag) {
    return (oldDetailedState & flag) != (newDetailedState & flag);
  }

  /**
   * Returns whether a flag bit is set on a bitmask.
   *
   * @param detailedState The bitmask.
   * @param flag The flag bit to check if is set.
   */
  private static boolean isSet(int detailedState, int flag) {
    return (detailedState & flag) != 0;
  }

  /**
   * A {@link FrameCallback} that calls
   * {@link com.google.android.material.motion.runtime.Performer.ManualPerformance#update(float)}
   * on each frame for every active
   * {@link com.google.android.material.motion.runtime.Performer.ManualPerformance}.
   */
  private class ManualPerformanceFrameCallback extends FrameCallback {

    private double lastTimeMs = 0.0;

    public void start() {
      lastTimeMs = 0.0;
      choreographer.postFrameCallback(this);
    }

    public void stop() {
      choreographer.removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
      for (TargetScope activeTarget : activeManualPerformanceTargets) {
        double frameTimeMs = frameTimeNanos / 1000;
        float deltaTimeMs = lastTimeMs == 0.0 ? 0f : (float) (frameTimeMs - lastTimeMs);

        activeTarget.update(deltaTimeMs);
      }
    }
  }
}
