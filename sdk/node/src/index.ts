/**
 * webscrape-ai — the official Node/TypeScript SDK for the webscrape.ai API.
 *
 * @see https://webscrape.ai/docs
 */

export { Webscrape, SmartBrowse } from "./client";
export type { WebscrapeOptions, WaitOptions } from "./client";
export type { RequestOptions, FetchImpl } from "./http";
export { VERSION } from "./version";

export {
  WebscrapeError,
  APIError,
  AuthenticationError,
  InsufficientCreditsError,
  EmailVerificationError,
  ForbiddenError,
  NotFoundError,
  ConflictError,
  BadRequestError,
  ValidationError,
  RateLimitError,
  ServerError,
  ConfigurationError,
  TransportError,
  TimeoutError,
  RunFailedError,
  WaitTimeoutError,
  type ApiErrorInit,
} from "./errors";

export type {
  ParseMode,
  DetailLevel,
  PageComplexity,
  RunStatus,
  CompletedResponse,
  QueuedResponse,
  Envelope,
  ScrapeRequest,
  ScrapeData,
  ScrapeResponse,
  LinkInfo,
  PageMetadata,
  StructuredData,
  SmartScraperRequest,
  SmartScraperData,
  SmartScraperResponse,
  SmartBrowseRunDispatchData,
  SmartBrowseRunDispatchResponse,
  SmartBrowseRunData,
  SmartBrowseRunResult,
  SmartBrowseRunResultPage,
  SmartBrowseRunResponse,
  SmartBrowseLastRun,
  SmartBrowseUsageData,
  SmartBrowseUsageResponse,
} from "./types";
