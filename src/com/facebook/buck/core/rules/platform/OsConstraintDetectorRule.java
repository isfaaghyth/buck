/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import com.facebook.buck.core.model.platform.ProvidesHostConstraintDetector;
import com.facebook.buck.core.model.platform.impl.OsConstraintDetector;
import com.facebook.buck.core.rules.config.ConfigurationRule;

/** A configuration rule that represents {@code os_constraint_detector} target. */
public class OsConstraintDetectorRule implements ConfigurationRule, ProvidesHostConstraintDetector {

  private final BuildTarget buildTarget;
  private final OsConstraintDetector osConstraintDetector;

  public OsConstraintDetectorRule(
      BuildTarget buildTarget, OsConstraintDetector osConstraintDetector) {
    this.buildTarget = buildTarget;
    this.osConstraintDetector = osConstraintDetector;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public HostConstraintDetector getHostConstraintDetector() {
    return osConstraintDetector;
  }
}
