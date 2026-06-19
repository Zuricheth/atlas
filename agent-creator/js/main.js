import { initEmbers } from "./embers.js";
import { initSpotlight } from "./spotlight.js";
import { bindFormDelegation, bindStepNavDelegation, renderWizard } from "./wizard.js";
import { renderStepNav, renderPreview } from "./preview.js";
import { bindExport } from "./export.js";
import { bindChat } from "./events.js";

function bootstrap() {
  initEmbers(document.querySelector("#emberField"));
  initSpotlight(document.querySelector("#spotlight"));
  bindFormDelegation();
  bindStepNavDelegation();
  renderStepNav();
  renderWizard();
  renderPreview();
  bindExport();
  bindChat();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", bootstrap);
} else {
  bootstrap();
}
