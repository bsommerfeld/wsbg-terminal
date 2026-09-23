package de.bsommerfeld.wsbg.terminal.fx;

/** An intermediate base that passes the model type through - like a widget frame would. */
abstract class Framed<M extends ViewModel> extends FxmlNode<M> {
}
