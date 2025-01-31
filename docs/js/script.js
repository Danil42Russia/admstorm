const defaultServerName = "server";

const serverNameProvider = () => {
  return (urlParams.get("server_name") || defaultServerName)
    .replaceAll(/[^a-z0-9]/g, "");
};

const urlParams = new URLSearchParams(window.location.search);
const serverName = serverNameProvider();

window.onload = () => {
  const serverNameSpans = document.querySelectorAll(".server-name");
  serverNameSpans.forEach((el) => {
    el.innerHTML = `<code class='chapter__code-block__inline'>${serverName}</code>`;
  });

  if (serverName !== defaultServerName) {
    const innerLinks = document.querySelectorAll(".inner-link");
    innerLinks.forEach((el) => {
      el.href = el.href + `?server_name=${serverName}`;
    });

    const securityImages = document.querySelectorAll(".js-security-image");
    securityImages.forEach((el) => {
      el.src = el.src.replace(".png", `.${serverName}.png`);
    });
  }

  const securityVideos = document.querySelectorAll(".js-security-video");
  securityVideos.forEach((el) => {
    const src = el.dataset.src;

    if (serverName !== defaultServerName) {
      el.innerHTML = `<video class="chapter__version-block-new-feature__video" controls="controls" preload="auto">
    <source src="${src}.${serverName}.mp4" type='video/mp4;"'>
</video>`;
    } else {
      el.innerHTML = `<img class="chapter__version-block-new-feature__image" src="${src}.png" alt=""/>`;
    }
  });
};
