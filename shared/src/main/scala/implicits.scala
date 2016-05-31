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

import cats.Eval
import scala.concurrent.{Promise, Future, ExecutionContext}

object implicits {
  implicit def fetchFutureFetchMonadError(
      implicit ec: ExecutionContext
  ): FetchMonadError[Future] = new FetchMonadError[Future] {
    override def runQuery[A](j: Query[A]): Future[A] = j match {
      case Now(x)   => Future.successful(x)
      case Later(x) => Future({ x() })
      case Async(ac) => {
          val p = Promise[A]()

          ec.execute(new Runnable {
            def run() = ac(p.trySuccess _, p.tryFailure _)
          })

          p.future
        }
    }
    def pure[A](x: A): Future[A] = Future.successful(x)
    def handleErrorWith[A](fa: Future[A])(f: Throwable => Future[A]): Future[A] =
      fa.recoverWith({ case t => f(t) })
    def raiseError[A](e: Throwable): Future[A]                     = Future.failed(e)
    def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
  }
}
