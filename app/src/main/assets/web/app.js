'use strict';

// O que o aparelho manda no lugar de um segredo já guardado, e o que devolvemos para mantê-lo.
// Ver ConfigurationJson.SECRET_PLACEHOLDER.
const SEGREDO = '__guardada__';

// Os três pares largura/altura viajam juntos num <select> só; aqui está a tradução para os campos
// que a configuração realmente tem.
const TAMANHOS = {
  captureSize: ['captureWidth', 'captureHeight'],
  scoreboardCaptureSize: ['scoreboardCaptureWidth', 'scoreboardCaptureHeight'],
  clockCaptureSize: ['clockCaptureWidth', 'clockCaptureHeight'],
};

const estado = { config: null, capacidades: null, imagens: {} };

document.addEventListener('DOMContentLoaded', iniciar);

async function iniciar() {
  document.querySelectorAll('#tabs button').forEach((botao) => {
    botao.addEventListener('click', () => mostrarTela(botao.dataset.screen));
  });
  document.querySelectorAll('[data-preview]').forEach((botao) => {
    botao.addEventListener('click', () => atualizarPrevia(botao.dataset.preview, botao.dataset.target));
  });
  document.querySelectorAll('[data-command]').forEach((botao) => {
    botao.addEventListener('click', () => executar(botao.dataset.command));
  });
  document.getElementById('save').addEventListener('click', salvar);
  document.getElementById('logo-file').addEventListener('change', enviarIcone);
  document.getElementById('crop-larger').addEventListener('click', () => mudarZoom(-0.25));
  document.getElementById('crop-smaller').addEventListener('click', () => mudarZoom(0.25));
  document.getElementById('destination-target').addEventListener('change', () => desenhar('composition'));

  prepararCantos('scoreboard');
  prepararCantos('clock');
  prepararRecorte();
  prepararDestino();

  try {
    estado.capacidades = await pegarJson('/api/capabilities');
    estado.config = await pegarJson('/api/config');
  } catch (erro) {
    anunciar('Não consegui falar com o aparelho: ' + erro.message);
    return;
  }
  montarOpcoes();
  aplicarConfiguracao(estado.config);
  atualizarStatus();
  anunciar('Conectado ao aparelho.');
}

function anunciar(texto) {
  document.getElementById('status-line').textContent = texto;
}

function mostrarTela(nome) {
  document.querySelectorAll('#tabs button').forEach((botao) => {
    botao.classList.toggle('selected', botao.dataset.screen === nome);
  });
  document.querySelectorAll('main section').forEach((secao) => {
    secao.hidden = secao.id !== 'screen-' + nome;
  });
}

async function pegarJson(caminho) {
  const resposta = await fetch(caminho, { cache: 'no-store' });
  if (!resposta.ok) throw new Error(await resposta.text());
  return resposta.json();
}

// ---------------------------------------------------------------- opções dos campos

function montarOpcoes() {
  const capacidades = estado.capacidades;
  document.querySelectorAll('[data-enum]').forEach((select) => {
    preencher(select, capacidades.enums[select.dataset.enum] || []);
  });
  document.querySelectorAll('[data-cameras]').forEach((select) => {
    preencher(select, capacidades.cameras.map((camera) => ({ value: camera.id, label: camera.label })));
    select.addEventListener('change', () => {
      atualizarListasDaCamera();
      desenhar('court');
    });
  });
  atualizarListasDaCamera();
}

function preencher(select, opcoes, selecionado) {
  const anterior = selecionado !== undefined ? String(selecionado) : select.value;
  select.textContent = '';
  opcoes.forEach((opcao) => {
    const elemento = document.createElement('option');
    elemento.value = String(opcao.value);
    elemento.textContent = opcao.label;
    select.appendChild(elemento);
  });
  if (anterior !== '') select.value = anterior;
  if (select.selectedIndex < 0 && select.options.length > 0) select.selectedIndex = 0;
}

/** Resolução e FPS dependem da câmera escolhida naquela tela, então mudam junto com ela. */
function atualizarListasDaCamera() {
  document.querySelectorAll('[data-sizes]').forEach((select) => {
    const camera = valorDe(document.querySelector('[data-field="' + select.dataset.sizes + '"]'));
    const tamanhos = (estado.capacidades.captureSizes[camera] || []).map((tamanho) => ({
      value: tamanho.width + 'x' + tamanho.height,
      label: tamanho.label,
    }));
    preencher(select, [{ value: '0x0', label: 'Automática' }].concat(tamanhos));
  });
  document.querySelectorAll('[data-fps]').forEach((select) => {
    const camera = valorDe(document.querySelector('[data-field="' + select.dataset.fps + '"]'));
    const taxas = (estado.capacidades.captureFps[camera] || []).map((fps) => ({ value: fps, label: fps + ' fps' }));
    preencher(select, [{ value: 0, label: 'Automático' }].concat(taxas));
  });
}

function valorDe(elemento) {
  return elemento ? elemento.value : '';
}

// ---------------------------------------------------------------- configuração

/**
 * Ordem importa: resolução e FPS de cada fonte só existem depois que a câmera daquela tela está
 * escolhida, porque a lista sai das capacidades dela. Então os campos que dependem da câmera ficam
 * para o segundo passo.
 */
function aplicarConfiguracao(config) {
  estado.config = config;
  const dependeDaCamera = (elemento) => elemento.dataset.sizes || elemento.dataset.fps;
  document.querySelectorAll('[data-field]').forEach((elemento) => {
    if (dependeDaCamera(elemento)) return;
    escrever(elemento, config);
  });
  atualizarListasDaCamera();
  document.querySelectorAll('[data-field]').forEach((elemento) => {
    if (dependeDaCamera(elemento)) escrever(elemento, config);
  });
  atualizarEtiquetas();
  desenharTudo();
}

function escrever(elemento, config) {
  const nome = elemento.dataset.field;
  if (TAMANHOS[nome]) {
    const [largura, altura] = TAMANHOS[nome];
    elemento.value = config[largura] + 'x' + config[altura];
    if (elemento.selectedIndex < 0) elemento.selectedIndex = 0;
    return;
  }
  const valor = config[nome];
  if (elemento.type === 'checkbox') elemento.checked = Boolean(valor);
  else if (elemento.dataset.scale) elemento.value = Math.round(valor * Number(elemento.dataset.scale));
  else if (elemento.type === 'password') elemento.value = valor === SEGREDO ? '' : valor;
  else elemento.value = valor;
  if (elemento.tagName === 'SELECT' && elemento.selectedIndex < 0) elemento.selectedIndex = 0;
}

function coletarConfiguracao() {
  const config = Object.assign({}, estado.config);
  document.querySelectorAll('[data-field]').forEach((elemento) => {
    const nome = elemento.dataset.field;
    if (TAMANHOS[nome]) {
      const [largura, altura] = TAMANHOS[nome];
      const partes = String(elemento.value).split('x');
      config[largura] = Number(partes[0]) || 0;
      config[altura] = Number(partes[1]) || 0;
      return;
    }
    if (elemento.type === 'checkbox') config[nome] = elemento.checked;
    else if (elemento.dataset.scale) config[nome] = Number(elemento.value) / Number(elemento.dataset.scale);
    else if (elemento.dataset.number) config[nome] = Number(elemento.value);
    // Campo de segredo em branco significa "mantenha o que já está no aparelho".
    else if (elemento.type === 'password') config[nome] = elemento.value === '' ? SEGREDO : elemento.value;
    else config[nome] = elemento.value;
  });
  return config;
}

async function salvar() {
  const aviso = document.getElementById('save-status');
  aviso.textContent = 'Salvando…';
  try {
    const resposta = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(coletarConfiguracao()),
    });
    if (!resposta.ok) throw new Error(await resposta.text());
    aplicarConfiguracao(await resposta.json());
    aviso.textContent = 'Salvo no aparelho.';
  } catch (erro) {
    aviso.textContent = 'Falhou: ' + erro.message;
  }
}

function atualizarEtiquetas() {
  const tamanho = document.querySelector('[data-field="logoWidth"]');
  document.getElementById('logo-size-status').textContent = tamanho.value + '% do vídeo';
  const zoom = estado.config.cropZoom || 1;
  document.getElementById('crop-status').textContent = 'Recorte: ' + zoom.toFixed(1).replace('.', ',') + '×';
}

// ---------------------------------------------------------------- ações e status

async function executar(comando) {
  anunciar('Executando…');
  try {
    const resposta = await fetch('/api/command/' + comando, { method: 'POST' });
    const corpo = await resposta.json();
    anunciar(corpo.mensagem);
    relatar(comando, corpo.mensagem);
  } catch (erro) {
    anunciar('Falhou: ' + erro.message);
  }
  atualizarStatus();
}

function relatar(comando, texto) {
  const destino = comando.startsWith('probe') ? 'probe-report'
    : comando.startsWith('youtube') ? 'youtube-report' : 'live-report';
  document.getElementById(destino).textContent = texto;
}

async function atualizarStatus() {
  try {
    const status = await pegarJson('/api/status');
    document.getElementById('toggle-live').textContent =
      status.transmitindo ? 'Encerrar transmissão' : 'Iniciar transmissão';
    document.getElementById('live-report').textContent = status.mensagem;
  } catch (erro) {
    anunciar('Sem status: ' + erro.message);
  }
}

async function enviarIcone(evento) {
  const arquivo = evento.target.files[0];
  if (!arquivo) return;
  anunciar('Enviando o ícone…');
  const resposta = await fetch('/api/logo', { method: 'POST', body: arquivo });
  const corpo = await resposta.json();
  anunciar(corpo.mensagem);
}

// ---------------------------------------------------------------- prévias

async function atualizarPrevia(fonte, alvo) {
  anunciar('Pedindo um quadro…');
  const resposta = await fetch('/api/preview/' + fonte + '.jpg', { cache: 'no-store' });
  if (!resposta.ok) {
    anunciar(await resposta.text());
    return;
  }
  const imagem = new Image();
  imagem.onload = () => {
    estado.imagens[fonte] = imagem;
    if (alvo) document.getElementById(alvo).src = imagem.src;
    else {
      const direto = document.getElementById('preview-' + fonte);
      if (direto) direto.src = imagem.src;
    }
    desenharTudo();
    anunciar('Quadro atualizado.');
  };
  imagem.src = URL.createObjectURL(await resposta.blob());
}

function desenharTudo() {
  ['court', 'scoreboard', 'clock', 'composition'].forEach(desenhar);
}

function contexto(nome) {
  const canvas = document.getElementById('canvas-' + nome);
  if (!canvas) return null;
  const imagem = estado.imagens[nome === 'composition' ? 'composed' : nome];
  const largura = canvas.clientWidth || 640;
  canvas.width = largura;
  canvas.height = Math.round(largura * (imagem ? imagem.height / imagem.width : 9 / 16));
  const pincel = canvas.getContext('2d');
  pincel.fillStyle = '#000000';
  pincel.fillRect(0, 0, canvas.width, canvas.height);
  if (imagem) pincel.drawImage(imagem, 0, 0, canvas.width, canvas.height);
  else {
    pincel.fillStyle = '#9EADBC';
    pincel.font = '14px system-ui, sans-serif';
    pincel.fillText('Toque em atualizar para pedir um quadro', 14, 26);
  }
  return { canvas: canvas, pincel: pincel };
}

function desenhar(nome) {
  const alvo = contexto(nome);
  if (!alvo) return;
  if (nome === 'court') desenharRecorte(alvo);
  else if (nome === 'composition') desenharDestinos(alvo);
  else desenharCantos(alvo, nome);
}

function centralizado16x9(largura, altura) {
  const alvo = 16 / 9;
  const proporcao = largura / altura;
  if (proporcao > alvo) {
    const normal = alvo / proporcao;
    return { left: (1 - normal) / 2, top: 0, right: (1 + normal) / 2, bottom: 1 };
  }
  const normal = proporcao / alvo;
  return { left: 0, top: (1 - normal) / 2, right: 1, bottom: (1 + normal) / 2 };
}

/** Mesma conta de NormalizedRect.adjustable16x9, para o site mostrar o recorte que vai ao ar. */
function recorteAtual(largura, altura) {
  const base = centralizado16x9(largura, altura);
  const config = estado.config;
  const zoom = Math.min(Math.max(config.cropZoom || 1, 1), 8);
  const w = (base.right - base.left) / zoom;
  const h = (base.bottom - base.top) / zoom;
  const cx = 0.5 + (config.cropPanX || 0) * (1 - w) / 2;
  const cy = 0.5 + (config.cropPanY || 0) * (1 - h) / 2;
  return { left: cx - w / 2, top: cy - h / 2, right: cx + w / 2, bottom: cy + h / 2 };
}

function desenharRecorte(alvo) {
  const rect = recorteAtual(alvo.canvas.width, alvo.canvas.height);
  alvo.pincel.strokeStyle = '#73E2A7';
  alvo.pincel.lineWidth = 2;
  alvo.pincel.strokeRect(
    rect.left * alvo.canvas.width, rect.top * alvo.canvas.height,
    (rect.right - rect.left) * alvo.canvas.width, (rect.bottom - rect.top) * alvo.canvas.height,
  );
}

function desenharCantos(alvo, nome) {
  const cantos = estado.config[nome + 'Corners'];
  if (!cantos) return;
  const pincel = alvo.pincel;
  pincel.strokeStyle = nome === 'scoreboard' ? '#F2C14E' : '#B58BE8';
  pincel.lineWidth = 2;
  pincel.beginPath();
  cantos.forEach((canto, indice) => {
    const x = canto.x * alvo.canvas.width;
    const y = canto.y * alvo.canvas.height;
    if (indice === 0) pincel.moveTo(x, y); else pincel.lineTo(x, y);
  });
  pincel.closePath();
  pincel.stroke();
  pincel.fillStyle = pincel.strokeStyle;
  cantos.forEach((canto) => {
    pincel.beginPath();
    pincel.arc(canto.x * alvo.canvas.width, canto.y * alvo.canvas.height, 9, 0, Math.PI * 2);
    pincel.fill();
  });
}

function desenharDestinos(alvo) {
  const selecionado = document.getElementById('destination-target').value;
  [['scoreboard', '#F2C14E'], ['clock', '#B58BE8']].forEach(([nome, cor]) => {
    const rect = estado.config[nome + 'Destination'];
    if (!rect) return;
    alvo.pincel.strokeStyle = cor;
    alvo.pincel.lineWidth = nome === selecionado ? 3 : 1;
    const x = rect.left * alvo.canvas.width;
    const y = rect.top * alvo.canvas.height;
    const w = (rect.right - rect.left) * alvo.canvas.width;
    const h = (rect.bottom - rect.top) * alvo.canvas.height;
    alvo.pincel.strokeRect(x, y, w, h);
    if (nome === selecionado) {
      alvo.pincel.fillStyle = cor;
      alvo.pincel.fillRect(x + w - 12, y + h - 12, 12, 12);
    }
  });
}

// ---------------------------------------------------------------- arrastar

function posicao(canvas, evento) {
  const area = canvas.getBoundingClientRect();
  return {
    x: (evento.clientX - area.left) / area.width,
    y: (evento.clientY - area.top) / area.height,
  };
}

function arrastar(canvas, aoMover) {
  let ativo = false;
  canvas.addEventListener('pointerdown', (evento) => {
    ativo = true;
    canvas.setPointerCapture(evento.pointerId);
    aoMover(posicao(canvas, evento), true);
  });
  canvas.addEventListener('pointermove', (evento) => {
    if (!ativo) return;
    evento.preventDefault();
    aoMover(posicao(canvas, evento), false);
  });
  const soltar = () => { ativo = false; };
  canvas.addEventListener('pointerup', soltar);
  canvas.addEventListener('pointercancel', soltar);
}

function prepararCantos(nome) {
  const canvas = document.getElementById('canvas-' + nome);
  let escolhido = -1;
  arrastar(canvas, (ponto, inicio) => {
    const cantos = estado.config[nome + 'Corners'];
    if (!cantos) return;
    if (inicio) {
      let menor = 0.05;
      escolhido = -1;
      cantos.forEach((canto, indice) => {
        const distancia = Math.hypot(canto.x - ponto.x, canto.y - ponto.y);
        if (distancia < menor) { menor = distancia; escolhido = indice; }
      });
      return;
    }
    if (escolhido < 0) return;
    cantos[escolhido] = {
      x: Math.min(Math.max(ponto.x, 0), 1),
      y: Math.min(Math.max(ponto.y, 0), 1),
    };
    desenhar(nome);
  });
}

function prepararRecorte() {
  const canvas = document.getElementById('canvas-court');
  let anterior = null;
  arrastar(canvas, (ponto, inicio) => {
    if (inicio) { anterior = ponto; return; }
    const rect = recorteAtual(canvas.width, canvas.height);
    const largura = rect.right - rect.left;
    const altura = rect.bottom - rect.top;
    // Pan vai de -1 a 1 sobre a folga que sobra depois do recorte; converter o arrasto exige
    // dividir por essa folga, senão o retângulo anda menos que o dedo.
    if (largura < 1) estado.config.cropPanX = limitar(estado.config.cropPanX + (ponto.x - anterior.x) * 2 / (1 - largura));
    if (altura < 1) estado.config.cropPanY = limitar(estado.config.cropPanY + (ponto.y - anterior.y) * 2 / (1 - altura));
    anterior = ponto;
    desenhar('court');
  });
}

function limitar(valor) {
  return Math.min(Math.max(valor, -1), 1);
}

function mudarZoom(passo) {
  estado.config.cropZoom = Math.min(Math.max((estado.config.cropZoom || 1) + passo, 1), 8);
  atualizarEtiquetas();
  desenhar('court');
}

function prepararDestino() {
  const canvas = document.getElementById('canvas-composition');
  let modo = null;
  let anterior = null;
  arrastar(canvas, (ponto, inicio) => {
    const nome = document.getElementById('destination-target').value;
    const rect = estado.config[nome + 'Destination'];
    if (!rect) return;
    if (inicio) {
      const perto = Math.hypot(ponto.x - rect.right, ponto.y - rect.bottom) < 0.05;
      modo = perto ? 'tamanho' : 'posicao';
      anterior = ponto;
      return;
    }
    const dx = ponto.x - anterior.x;
    const dy = ponto.y - anterior.y;
    if (modo === 'tamanho') {
      rect.right = Math.min(Math.max(rect.right + dx, rect.left + 0.05), 1);
      rect.bottom = Math.min(Math.max(rect.bottom + dy, rect.top + 0.05), 1);
    } else {
      const largura = rect.right - rect.left;
      const altura = rect.bottom - rect.top;
      rect.left = Math.min(Math.max(rect.left + dx, 0), 1 - largura);
      rect.top = Math.min(Math.max(rect.top + dy, 0), 1 - altura);
      rect.right = rect.left + largura;
      rect.bottom = rect.top + altura;
    }
    anterior = ponto;
    desenhar('composition');
  });
}
