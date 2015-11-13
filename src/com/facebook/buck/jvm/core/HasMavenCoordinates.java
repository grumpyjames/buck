/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.core;

import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;

public interface HasMavenCoordinates extends BuildRule {

  /**
   * Used to identify this library within a maven repository
   */
  Optional<String> getMavenCoords();

  public static final Predicate<BuildRule> MAVEN_COORDS_PRESENT_PREDICATE =
      new Predicate<BuildRule>() {
    @Override
    public boolean apply(BuildRule input) {
      return input instanceof HasMavenCoordinates &&
          ((HasMavenCoordinates) input).getMavenCoords().isPresent();
    }
  };
}
