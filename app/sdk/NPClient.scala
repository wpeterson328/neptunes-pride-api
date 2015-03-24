package sdk

import play.api.libs.json._
import sdk.http.impl.PlayWebService
import sdk.http.{RequestHolder, Response, WebService}
import sdk.model._
import sdk.tokenService.TokenService
import sdk.tokenService.impl.TokenServiceImpl

import scala.concurrent.{ExecutionContext, Future}

object NPClient {
  val rootUrl = "http://triton.ironhelmet.com"
  val authServiceUrl = s"$rootUrl/arequest"
  val metadataServiceUrl = s"$rootUrl/mrequest"
  val gameServiceUrl = s"$rootUrl/grequest"

  case class PlayerInfo(games: List[GameMetadata])
  case class UniverseReport(game: Game, players: Seq[Player], stars: Seq[Star])

  def exchangeForAuthToken(username: String, password: String, ws: WebService = PlayWebService, ts: TokenService = TokenServiceImpl)(implicit ec: ExecutionContext): Future[AuthToken] = {
    for {
      oCookie <- fetchAuthCookie(username, password)(ws, ec)
      token <- ts.getToken(oCookie)
    } yield token
  }

  private def fetchAuthCookie(username: String, password: String)(implicit webServiceProvider: WebService, ec: ExecutionContext): Future[Option[AuthCookie]] = {
    val loginUrl = s"$authServiceUrl/login"

    val data = Map(
      "type" -> Seq("login"),
      "alias" -> Seq(username),
      "password" -> Seq(password)
    )

    postFormData(loginUrl, data, None).map { response =>
      for {
        authCookie <- response.cookie("auth")
        cookieValue <- authCookie.value
      } yield {
        AuthCookie(cookieValue)
      }
    }
  }

  private def postFormData(url: String, data: Map[String, Seq[String]], oCookie: Option[AuthCookie])(implicit webServiceProvider: WebService, ec: ExecutionContext): Future[Response] = {
    val holder: RequestHolder = webServiceProvider.url(url)
      .withHeaders("Content-Type" -> "application/x-www-form-urlencoded; charset=UTF-8")

    val authedHolder = oCookie match {
      case Some(cookie) => holder.withHeaders("Cookie" -> s"auth=${cookie.value}")
      case None => holder
    }
    authedHolder.post(data)
  }
}

class NPClient(token: AuthToken)(implicit webServiceProvider: WebService = PlayWebService, tokenServiceProvider: TokenService = TokenServiceImpl) {
  import sdk.NPClient._

  private val orderEndpointUrl = s"$gameServiceUrl/order"

  def getOpenGames()(implicit ec: ExecutionContext): Future[Seq[GameMetadata]] = {
    for {
      cookie <- tokenServiceProvider.lookupCookie(token)
      playerInfo <- fetchPlayerInfo(cookie)
    } yield playerInfo.games
  }

  def getGameDetails(gameId: Long)(implicit ec: ExecutionContext): Future[Game] = {
    for {
      cookie <- tokenServiceProvider.lookupCookie(token)
      universeReport <- fetchFullUniverseReport(gameId, cookie)
    } yield universeReport.game
  }

  def getPlayers(gameId: Long)(implicit ec: ExecutionContext): Future[Seq[Player]] = {
    for {
      cookie <- tokenServiceProvider.lookupCookie(token)
      universeReport <- fetchFullUniverseReport(gameId, cookie)
    } yield universeReport.players
  }

  def getStars(gameId: Long)(implicit ec: ExecutionContext): Future[Seq[Star]] = {
    for {
      cookie <- tokenServiceProvider.lookupCookie(token)
      universeReport <- fetchFullUniverseReport(gameId, cookie)
    } yield universeReport.stars
  }

  def submitTurn(gameId: Long)(implicit ec: ExecutionContext): Future[Unit] = {
    tokenServiceProvider.lookupCookie(token).flatMap { cookie =>
      val data = Map(
        "type" -> Seq("order"),
        "order" -> Seq("force_ready"),
        "version" -> Seq("7"),
        "game_number" -> Seq(gameId.toString)
      )

      postFormData(orderEndpointUrl, data, Some(cookie))
    }.mapTo[Unit]
  }

  private def fetchPlayerInfo(cookie: AuthCookie)(implicit ec: ExecutionContext): Future[PlayerInfo] = {
    val initEndpointUrl = s"$metadataServiceUrl/init_player"

    val data = Map(
      "type" -> Seq("init_player")
    )

    postFormData(initEndpointUrl, data, Some(cookie)).map { response =>
      val jsGames = (response.json \\ "open_games").head.as[JsArray]

      val games = jsGames.value.map { jsonGame =>
        GameMetadata(
          gameId = (jsonGame \ "number").as[String].toLong,
          name = (jsonGame \ "name").as[String]
        )
      }

      PlayerInfo(games.toList)
    }
  }

  private def fetchFullUniverseReport(gameId: Long, cookie: AuthCookie)(implicit ec: ExecutionContext): Future[UniverseReport] = {
    val data = Map(
      "type" -> Seq("order"),
      "order" -> Seq("full_universe_report"),
      "version" -> Seq("7"),
      "game_number" -> Seq(gameId.toString)
    )

    postFormData(orderEndpointUrl, data, Some(cookie)).map { response =>
      parseUniverseReport(response.json \ "report")
    }
  }

  private def parseUniverseReport(jsReport: JsValue): UniverseReport = {
    val gameName = (jsReport \ "name").as[String]
    val gameDetails = parseGameDetails(jsReport)
    val gameStatus = parseGameStatus(jsReport)
    val gamePlayer = parseGamePlayer(jsReport)

    val game = Game(
      name = gameName,
      details = Some(gameDetails),
      status = Some(gameStatus),
      player = Some(gamePlayer)
    )

    val players: Seq[Player] = parsePlayers(jsReport)

    val stars: Seq[Star] = parseStars(jsReport)

    UniverseReport(game, players, stars)
  }

  private def parseGameDetails(jsReport: JsValue): GameDetails =
    GameDetails(
      turnBased = (jsReport \ "turn_based").as[Int] != 0,
      turnBasedTimeout = (jsReport \ "turn_based_time_out").as[Int],
      war = (jsReport \ "war").as[Int] != 0,
      tickRate = (jsReport \ "tick_rate").as[Int],
      productionRate = (jsReport \ "production_rate").as[Int],
      totalStars = (jsReport \ "total_stars").as[Int],
      starsForVictory = (jsReport \ "stars_for_victory").as[Int],
      tradeCost = (jsReport \ "trade_cost").as[Int],
      tradeScanned = (jsReport \ "trade_scanned").as[Int] != 0,
      carrierSpeed = (jsReport \ "fleet_speed").as[Double]
    )

  private def parseGameStatus(jsReport: JsValue): GameStatus =
    GameStatus(
      startTime = (jsReport \ "start_time").as[Long],
      now = (jsReport \ "now").as[Long],
      started = (jsReport \ "started").as[Boolean],
      paused = (jsReport \ "paused").as[Boolean],
      gameOver = (jsReport \ "game_over").as[Int] != 0,
      productions = (jsReport \ "productions").as[Int],
      productionCounter = (jsReport \ "production_counter").as[Int],
      tick = (jsReport \ "tick").as[Int],
      tickFragment = (jsReport \ "tick_fragment").as[Double]
    )

  private def parseGamePlayer(jsReport: JsValue): GamePlayer =
    GamePlayer(
      playerId = (jsReport \ "player_uid").as[Int],
      admin = (jsReport \ "admin").as[Int] > 0
    )

  /**
   * Takes a JSON "map" (an object with integer-named properties) and returns a stream of JsValues which is more useful
   * @param jsonMap The JsValue which contains all the elements in the map.
   * @return A Stream of the values in the same order
   */
  private def getJsonObjects(jsonMap: JsValue): Seq[JsValue] = jsonMap match {
    case jsObj: JsObject => jsObj.value.values.toSeq
    case _ => Seq()
  }

  private def parsePlayers(jsReport: JsValue): Seq[Player] =
    getJsonObjects(jsReport \ "players") map { jsPlayer =>
      parsePlayer(jsPlayer)
    } sortBy(_.playerId)

  def parsePlayer(jsonPlayer: JsValue): Player =
    Player(
      playerId = (jsonPlayer \ "uid").as[Int],
      totalEconomy = (jsonPlayer \ "total_economy").as[Int],
      totalIndustry = (jsonPlayer \ "total_industry").as[Int],
      totalScience = (jsonPlayer \ "total_science").as[Int],
      aiControlled = (jsonPlayer \ "ai").as[Int] != 0,
      totalStars = (jsonPlayer \ "total_stars").as[Int],
      totalCarriers = (jsonPlayer \ "total_fleets").as[Int],
      totalShips = (jsonPlayer \ "total_strength").as[Int],
      name = (jsonPlayer \ "alias").as[String],
      scanning = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "scanning" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "scanning" \ "level").as[Int]
      ),
      hyperspaceRange = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "propulsion" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "propulsion" \ "level").as[Int]
      ),
      terraforming = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "terraforming" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "terraforming" \ "level").as[Int]
      ),
      experimentation = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "research" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "research" \ "level").as[Int]
      ),
      weapons = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "weapons" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "weapons" \ "level").as[Int]
      ),
      banking = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "banking" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "banking" \ "level").as[Int]
      ),
      manufacturing = PlayerTechLevel(
        value = (jsonPlayer \ "tech" \ "manufacturing" \ "value").as[Double],
        level = (jsonPlayer \ "tech" \ "manufacturing" \ "level").as[Int]
      ),
      conceded = (jsonPlayer \ "conceded").as[Int] match {
        case 0 => PlayerConcededResult.active
        case 1 => PlayerConcededResult.quit
        case 2 => PlayerConcededResult.awayFromKeyboard
      },
      ready = (jsonPlayer \ "ready").as[Int] != 0,
      missedTurns =  (jsonPlayer \ "missed_turns").as[Int],
      renownToGive = (jsonPlayer \ "karma_to_give").as[Int]
    )

  private def parseStars(jsReport: JsValue): Seq[Star] = {
    getJsonObjects(jsReport \ "stars") map { jsStar =>
      parseStar(jsStar)
    } sortBy(_.starId)
  }

  private def parseStar(jsStar: JsValue): Star =
    Star(
      starId = (jsStar \ "uid").as[Int],
      name = (jsStar \ "n").as[String],
      playerId = (jsStar \ "puid").asOpt[Int],
      visible = (jsStar \ "v").as[String] != "0",
      position = Position(
        x = java.lang.Double.parseDouble((jsStar \ "x").as[String]),
        y = java.lang.Double.parseDouble((jsStar \ "y").as[String])
      ),
      economy = (jsStar \ "e").asOpt[Int],
      industry = (jsStar \ "i").asOpt[Int],
      science = (jsStar \ "s").asOpt[Int],
      naturalResources = (jsStar \ "nr").asOpt[Int],
      terraformedResources = (jsStar \ "r").asOpt[Int],
      warpGate = (jsStar \ "ga").asOpt[Int].map(_ != 0),
      ships = (jsStar \ "st").asOpt[Int]
    )
}
