/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch

/**
  * A `Cache` trait so the users of the library can provide their own cache.
  */
trait DataSourceCache {
  def update[A](k: DataSourceIdentity, v: A): DataSourceCache

  def get(k: DataSourceIdentity): Option[Any]

  def cacheResults[I, A](results: Map[I, A], ds: DataSource[I, A]): DataSourceCache = {
    results.foldLeft(this)({
      case (acc, (i, a)) => acc.update(ds.identity(i), a)
    })
  }
}

/**
  * A cache that stores its elements in memory.
  */
case class InMemoryCache(state: Map[DataSourceIdentity, Any]) extends DataSourceCache {
  override def get(k: DataSourceIdentity): Option[Any] =
    state.get(k)

  override def update[A](k: DataSourceIdentity, v: A): InMemoryCache =
    copy(state = state.updated(k, v))
}

object InMemoryCache {
  def empty: InMemoryCache = InMemoryCache(Map.empty[DataSourceIdentity, Any])

  def apply(results: (DataSourceIdentity, Any)*): InMemoryCache =
    InMemoryCache(
        results.foldLeft(Map.empty[DataSourceIdentity, Any])({
      case (c, (k, v)) => c.updated(k, v)
    }))
}
